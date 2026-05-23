package gg.clipmaker.ui;

/**
 * Fat JAR entry point.
 *
 * The JVM refuses to launch a fat JAR whose Main-Class directly extends
 * javafx.application.Application because it detects that the JavaFX module
 * graph is not set up. Using a plain class as the entry point bypasses that
 * check while still delegating to the real JavaFX Application.
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}