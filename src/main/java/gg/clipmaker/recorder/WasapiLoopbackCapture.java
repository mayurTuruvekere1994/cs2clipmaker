package gg.clipmaker.recorder;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.RandomAccessFile;

/**
 * Captures system audio (loopback from the default render device) to a WAV file
 * using the Windows WASAPI COM API directly via JNA.
 *
 * This bypasses FFmpeg's WASAPI input entirely, so it works regardless of which
 * FFmpeg build is installed. It captures whatever is playing through the user's
 * speakers/headphones — CS2 game audio, hit sounds, footsteps, everything.
 */
public class WasapiLoopbackCapture implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WasapiLoopbackCapture.class);

    // Windows WASAPI COM GUIDs — static Memory (never GC'd, safe to pass across JNA calls)
    private static final Memory CLSID_MMDeviceEnumerator = guidMem("BCDE0395-E52F-467C-8E3D-C4579291692E");
    private static final Memory IID_IMMDeviceEnumerator  = guidMem("A95664D2-9614-4F35-A746-DE8DB63617E6");
    private static final Memory IID_IAudioClient         = guidMem("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
    private static final Memory IID_IAudioCaptureClient  = guidMem("C8ADBD64-E71E-48A0-A4DE-185C395CD317");

    // WASAPI / COM constants
    private static final int  CLSCTX_ALL                   = 0x17;
    private static final int  AUDCLNT_SHAREMODE_SHARED     = 0;
    private static final int  AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int  AUDCLNT_BUFFERFLAGS_SILENT   = 0x00000002;
    private static final long REFTIMES_PER_SEC             = 10_000_000L; // 100-ns units
    private static final int  eRender                      = 0;
    private static final int  eConsole                     = 0;

    // Minimal ole32.dll bindings — only what we need
    interface Ole32 extends StdCallLibrary {
        Ole32 INSTANCE = Native.load("ole32", Ole32.class);
        int  CoInitializeEx(Pointer reserved, int dwCoInit);
        void CoUninitialize();
        int  CoCreateInstance(Pointer rclsid, Pointer pUnkOuter, int dwClsCtx,
                              Pointer riid, PointerByReference ppv);
        void CoTaskMemFree(Pointer pv);
    }

    private final String     outputWav;
    private volatile boolean running = true;
    private volatile boolean success = false;

    public WasapiLoopbackCapture(String outputWav) { this.outputWav = outputWav; }
    public void    stop()      { running = false; }
    public boolean isSuccess() { return success; }

    @Override
    public void run() {
        Ole32.INSTANCE.CoInitializeEx(null, 0); // COINIT_MULTITHREADED

        Pointer enumerator    = null;
        Pointer device        = null;
        Pointer audioClient   = null;
        Pointer captureClient = null;
        Pointer pWaveFormat   = null;

        try (RandomAccessFile wav = new RandomAccessFile(outputWav, "rw")) {

            // 1. IMMDeviceEnumerator
            PointerByReference ppEnum = new PointerByReference();
            ok("CoCreateInstance", Ole32.INSTANCE.CoCreateInstance(
                CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, ppEnum));
            enumerator = ppEnum.getValue();
            if (enumerator == null) throw new RuntimeException("CoCreateInstance returned null enumerator");

            // 2. Default render endpoint (speakers / headphones)
            // vtable: [0]=QueryInterface [1]=AddRef [2]=Release [3]=EnumAudioEndpoints [4]=GetDefaultAudioEndpoint
            PointerByReference ppDevice = new PointerByReference();
            ok("GetDefaultAudioEndpoint", com(enumerator, 4, eRender, eConsole, ppDevice));
            device = ppDevice.getValue();
            if (device == null) throw new RuntimeException("GetDefaultAudioEndpoint returned null");

            // 3. IAudioClient via IMMDevice::Activate
            // vtable: [0..2]=IUnknown [3]=Activate
            PointerByReference ppClient = new PointerByReference();
            ok("Activate", com(device, 3, IID_IAudioClient, CLSCTX_ALL, Pointer.NULL, ppClient));
            audioClient = ppClient.getValue();
            if (audioClient == null) throw new RuntimeException("Activate returned null audioClient");

            // 4. Query the device's native mix format
            // IAudioClient vtable: [0..2]=IUnknown [3]=Initialize [4]=GetBufferSize [5]=GetStreamLatency
            //   [6]=GetCurrentPadding [7]=IsFormatSupported [8]=GetMixFormat [9]=GetDevicePeriod
            //   [10]=Start [11]=Stop [12]=Reset [13]=SetEventHandle [14]=GetService
            PointerByReference ppFmt = new PointerByReference();
            ok("GetMixFormat", com(audioClient, 8, ppFmt));
            pWaveFormat = ppFmt.getValue();

            short wFormatTag     = pWaveFormat.getShort(0);
            short nChannels      = pWaveFormat.getShort(2);
            int   nSampleRate    = pWaveFormat.getInt(4);
            int   nAvgBytesPerSec= pWaveFormat.getInt(8);
            short nBlockAlign    = pWaveFormat.getShort(12);
            short wBitsPerSample = pWaveFormat.getShort(14);
            short cbSize         = pWaveFormat.getShort(16);

            log.info("WASAPI mix format: tag=0x{} ch={} rate={} bits={} cbSize={}",
                Integer.toHexString(wFormatTag & 0xFFFF), nChannels, nSampleRate, wBitsPerSample, cbSize);

            // 5. Initialize in shared loopback mode
            ok("Initialize", com(audioClient, 3,
                AUDCLNT_SHAREMODE_SHARED,
                AUDCLNT_STREAMFLAGS_LOOPBACK,
                REFTIMES_PER_SEC,   // 1-second internal buffer
                0L,                 // periodicity = 0 for shared mode
                pWaveFormat,
                Pointer.NULL));

            // 6. IAudioCaptureClient
            // IAudioCaptureClient vtable: [0..2]=IUnknown [3]=GetBuffer [4]=ReleaseBuffer [5]=GetNextPacketSize
            PointerByReference ppCapture = new PointerByReference();
            ok("GetService", com(audioClient, 14, IID_IAudioCaptureClient, ppCapture));
            captureClient = ppCapture.getValue();
            if (captureClient == null) throw new RuntimeException("GetService returned null captureClient");

            // 7. Write WAV header with size placeholders (patched after capture)
            writeWavHeader(wav, wFormatTag, nChannels, nSampleRate, nAvgBytesPerSec,
                           nBlockAlign, wBitsPerSample, cbSize, pWaveFormat);
            long dataStart = wav.getFilePointer(); // byte offset of first audio sample

            // 8. Start loopback capture
            ok("Start", com(audioClient, 10));
            log.info("WASAPI loopback capture started → {}", outputWav);

            IntByReference    pPacketLen = new IntByReference();
            IntByReference    pNumFrames = new IntByReference();
            IntByReference    pFlags     = new IntByReference();
            PointerByReference ppData    = new PointerByReference();
            long totalBytes = 0;
            int  blockAlign = Short.toUnsignedInt(nBlockAlign);

            while (running) {
                com(captureClient, 5, pPacketLen);   // GetNextPacketSize
                if (pPacketLen.getValue() == 0) {
                    Thread.sleep(5);
                    continue;
                }
                // GetBuffer
                com(captureClient, 3, ppData, pNumFrames, pFlags, Pointer.NULL, Pointer.NULL);
                int frames = pNumFrames.getValue();
                if (frames > 0) {
                    int bytes = frames * blockAlign;
                    if ((pFlags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0) {
                        wav.write(new byte[bytes]);           // silent frame → zeros
                    } else {
                        wav.write(ppData.getValue().getByteArray(0, bytes));
                    }
                    totalBytes += bytes;
                }
                com(captureClient, 4, frames);       // ReleaseBuffer
            }

            com(audioClient, 11);  // Stop

            // 9. Patch RIFF and data chunk sizes now that we know the total
            long fileEnd = wav.getFilePointer();
            wav.seek(4);
            writeLE32(wav, (int)(fileEnd - 8));
            wav.seek(dataStart - 4);
            writeLE32(wav, (int) totalBytes);

            success = totalBytes > 0;
            log.info("WASAPI loopback stopped — {} bytes ({} s) written to {}",
                totalBytes, totalBytes / nAvgBytesPerSec, outputWav);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("WASAPI loopback capture failed: {}", e.getMessage(), e);
        } finally {
            if (pWaveFormat != null) Ole32.INSTANCE.CoTaskMemFree(pWaveFormat);
            rel(captureClient);
            rel(audioClient);
            rel(device);
            rel(enumerator);
            Ole32.INSTANCE.CoUninitialize();
        }
    }

    // ── WAV writing ───────────────────────────────────────────────────────────

    private static void writeWavHeader(RandomAccessFile f,
            short wFormatTag, short nChannels, int sampleRate, int avgBytesPerSec,
            short blockAlign, short bitsPerSample, short cbSize, Pointer pWaveFormat)
            throws java.io.IOException {
        int extra   = Short.toUnsignedInt(cbSize);
        int fmtSize = 18 + extra; // base WAVEFORMATEX (18 B) + extended fields

        f.write("RIFF".getBytes()); writeLE32(f, 0);           // RIFF size: patched later
        f.write("WAVE".getBytes());
        f.write("fmt ".getBytes()); writeLE32(f, fmtSize);
        writeLE16(f, wFormatTag);
        writeLE16(f, nChannels);
        writeLE32(f, sampleRate);
        writeLE32(f, avgBytesPerSec);
        writeLE16(f, blockAlign);
        writeLE16(f, bitsPerSample);
        writeLE16(f, cbSize);
        if (extra > 0) f.write(pWaveFormat.getByteArray(18, extra)); // WAVEFORMATEXTENSIBLE fields
        if (fmtSize % 2 != 0) f.write(0);                     // RIFF chunks must be word-aligned
        f.write("data".getBytes()); writeLE32(f, 0);           // data size: patched later
    }

    private static void writeLE32(RandomAccessFile f, int v) throws java.io.IOException {
        f.write(v & 0xFF); f.write((v >> 8) & 0xFF);
        f.write((v >> 16) & 0xFF); f.write((v >> 24) & 0xFF);
    }

    private static void writeLE16(RandomAccessFile f, short v) throws java.io.IOException {
        f.write(v & 0xFF); f.write((v >> 8) & 0xFF);
    }

    // ── JNA / COM helpers ─────────────────────────────────────────────────────

    /** Call COM vtable method at index {@code idx} on {@code obj}, return HRESULT. */
    private static int com(Pointer obj, int idx, Object... args) {
        Pointer fn = obj.getPointer(0).getPointer((long) idx * Native.POINTER_SIZE);
        Object[] a = new Object[args.length + 1];
        a[0] = obj;
        System.arraycopy(args, 0, a, 1, args.length);
        return Function.getFunction(fn, Function.ALT_CONVENTION).invokeInt(a);
    }

    private static void rel(Pointer p) {
        if (p != null) try { com(p, 2); } catch (Exception ignored) {} // IUnknown::Release
    }

    private static void ok(String op, int hr) {
        if (hr < 0) throw new RuntimeException(op + " HRESULT=0x" + Integer.toHexString(hr));
    }

    /**
     * Allocate a pinned native Memory block containing the 16-byte GUID in Windows COM wire format.
     * Data1/2/3 are little-endian; Data4 is big-endian.
     * Stored in a static field so it is never GC'd between the JNA call and the native code reading it.
     */
    private static Memory guidMem(String s) {
        String h = s.replace("{", "").replace("}", "").replace("-", "");
        Memory m = new Memory(16);
        int d1 = (int) Long.parseLong(h.substring(0,  8), 16);
        m.setByte(0, (byte)d1); m.setByte(1, (byte)(d1>>8));
        m.setByte(2, (byte)(d1>>16)); m.setByte(3, (byte)(d1>>24));
        int d2 = Integer.parseInt(h.substring(8,  12), 16);
        m.setByte(4, (byte)d2); m.setByte(5, (byte)(d2>>8));
        int d3 = Integer.parseInt(h.substring(12, 16), 16);
        m.setByte(6, (byte)d3); m.setByte(7, (byte)(d3>>8));
        for (int i = 0; i < 8; i++)
            m.setByte(8 + i, (byte) Integer.parseInt(h.substring(16 + i*2, 18 + i*2), 16));
        return m;
    }
}
