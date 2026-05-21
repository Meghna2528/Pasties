package com.pasties.ui;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.Window;
import java.awt.KeyEventDispatcher;
import java.awt.event.KeyEvent;

/**
 * Small UI helper for making Pasties surfaces dismiss consistently with Escape.
 */
final class EscapeToClose {

    private EscapeToClose() {}

    static void install(Window window) {
        if (window instanceof javax.swing.RootPaneContainer container) {
            install(container.getRootPane(), window::dispose);
        }
    }

    static void install(JRootPane rootPane, Runnable closeAction) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "pasties.close");
        rootPane.getActionMap().put("pasties.close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                closeAction.run();
            }
        });
    }

    static KeyEventDispatcher globalDispatcher(Runnable closeAction) {
        return event -> {
            if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                closeAction.run();
                return true;
            }
            return false;
        };
    }
}
