package com.warehouse.client.ui;

import javax.swing.*;
import java.awt.*;

public final class AppTheme {
    private AppTheme() {
    }

    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the default Swing look and feel if the platform theme is unavailable.
        }
        Font base = UIManager.getFont("Label.font");
        if (base != null) {
            Font readable = base.deriveFont(14f);
            for (String key : new String[]{"Label.font", "Button.font", "TextField.font",
                    "PasswordField.font", "Table.font", "TableHeader.font", "OptionPane.messageFont"}) {
                UIManager.put(key, readable);
            }
        }
        UIManager.put("Table.rowHeight", 32);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
    }
}
