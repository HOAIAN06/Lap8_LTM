package com.warehouse.client.ui;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/** A small Swing button whose colors do not depend on the Windows look and feel. */
public final class AppButton extends JButton {
    private static final Color TEAL = new Color(15, 139, 132);
    private static final Color INK = new Color(27, 47, 68);
    private static final Color MUTED = new Color(112, 132, 152);
    private static final Color BORDER = new Color(211, 223, 232);
    private static final Color DISABLED = new Color(231, 239, 240);
    private final boolean primary;

    public AppButton(String text, boolean primary) {
        super(text);
        this.primary = primary;
        setFont(getFont().deriveFont(Font.BOLD, 14f));
        setMargin(new Insets(11, 16, 11, 16));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean active = isEnabled();
        Color fill = !active ? DISABLED : primary ? TEAL : Color.WHITE;
        Color outline = active && primary ? TEAL : BORDER;
        if (active && getModel().isPressed()) fill = primary ? TEAL.darker() : new Color(242, 247, 249);
        g.setColor(fill);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
        g.setColor(outline);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
        g.setFont(getFont());
        g.setColor(!active ? MUTED : primary ? Color.WHITE : INK);
        FontMetrics metrics = g.getFontMetrics();
        int x = (getWidth() - metrics.stringWidth(getText())) / 2;
        int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(getText(), x, y);
        g.dispose();
    }
}
