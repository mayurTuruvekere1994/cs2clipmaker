package gg.clipmaker.editor;

import gg.clipmaker.model.HighlightEvent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-written hook lines shown at the very start of each short to grab viewer attention.
 * Add or edit entries in the static blocks below to customise the pool.
 *
 * Selection is deterministic: the same event always gets the same hook so re-renders
 * are consistent. AIHookGenerator is tried first; these are the fallback.
 */
public class HookTextBank {

    private static final Map<HighlightEvent.Type, List<String>> ROUND_HOOKS =
            new EnumMap<>(HighlightEvent.Type.class);

    private static final List<String> HIGHLIGHTS_HOOKS;

    static {
        ROUND_HOOKS.put(HighlightEvent.Type.SINGLE_KILL, List.of(
                "WATCH THIS",
                "THEY DIDN'T EXPECT IT",
                "CLEAN AIM, CLEAN KILL",
                "POV: AIMING DIFFERENT TODAY",
                "ONE SHOT, ONE STORY"
        ));
        ROUND_HOOKS.put(HighlightEvent.Type.DOUBLE_KILL, List.of(
                "DOUBLE DOWN",
                "2 FOR THE PRICE OF 1",
                "THEY NEVER SAW IT COMING",
                "QUICK WORK",
                "TWO AND DONE"
        ));
        ROUND_HOOKS.put(HighlightEvent.Type.TRIPLE_KILL, List.of(
                "3 KILLS. ONE ROUND. WATCH.",
                "THE LOBBY WASN'T READY",
                "THIS 3K CAME OUT OF NOWHERE",
                "THEY NEVER STOOD A CHANCE",
                "POV: YOU CAN'T BE STOPPED",
                "WATCH HOW FAST THIS ENDS",
                "3 DOWN IN SECONDS"
        ));
        ROUND_HOOKS.put(HighlightEvent.Type.QUAD_KILL, List.of(
                "4K IN ONE ROUND",
                "THE WHOLE SQUAD FELL",
                "NOT EVEN CLOSE",
                "THEY HAD NO ANSWER FOR THIS",
                "4 DOWN. WATCH.",
                "THIS SHOULDN'T BE POSSIBLE",
                "4 KILLS AND THEY STILL LOST"
        ));
        ROUND_HOOKS.put(HighlightEvent.Type.ACE, List.of(
                "HE JUST ACED THE ENTIRE TEAM",
                "5 KILLS. ONE ROUND.",
                "THE ROUND THAT BROKE THEM",
                "ACE INCOMING. DON'T BLINK.",
                "THEY STACKED. IT DIDN'T MATTER.",
                "1 PLAYER. 5 PROBLEMS. 0 SURVIVORS.",
                "THEY CALLED IT A 5V1..."
        ));

        HIGHLIGHTS_HOOKS = List.of(
                "EVERY KILL FROM THE MATCH",
                "THE BEST CLIPS FROM TODAY",
                "POV: CARRYING ALL DAY",
                "MATCH HIGHLIGHTS. WATCH.",
                "THIS IS HOW THE MATCH WENT",
                "WATCH WHAT HAPPENED TODAY",
                "FULL MATCH. FULL SEND."
        );
    }

    /**
     * Picks a hook for a per-round short.
     * Deterministic: same event → same hook on every render.
     */
    public static String pick(HighlightEvent event) {
        List<String> pool = ROUND_HOOKS.getOrDefault(
                event.getType(), ROUND_HOOKS.get(HighlightEvent.Type.TRIPLE_KILL));
        return pool.get((int) (Math.abs(event.getTimestampMs()) % pool.size()));
    }

    /**
     * Picks a hook for the match highlights shorts.
     * @param seed first-event timestamp — ensures consistent selection per session.
     */
    public static String pickForHighlights(long seed) {
        return HIGHLIGHTS_HOOKS.get((int) (Math.abs(seed) % HIGHLIGHTS_HOOKS.size()));
    }
}
