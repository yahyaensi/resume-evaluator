package com.hr.gui;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.MouseAdapter;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Voile semi-transparent avec un spinner au centre, posé sur le contenu de la fenêtre pendant un traitement.
 *
 * <p>Il bloque la souris et le clavier sur tout le contenu, <b>sauf</b> sur un composant laissé libre
 * (le bouton « Annuler ») : sur sa surface, {@link #contains(int, int)} renvoie {@code false}, donc Swing
 * transmet les événements au composant situé dessous. La barre de titre de la fenêtre reste, elle aussi, utilisable.
 */
final class BusyGlassPane extends JComponent {

    private static final int SPINNER_SIZE = 56;
    private static final int STROKE = 6;
    private static final Color FALLBACK_ACCENT = Color.decode("#2563eb");

    private final JComponent area;
    private final JComponent passThrough;
    private final Component restoreFocusTo;
    private final Timer timer;
    private int angle;

    /**
     * @param area           composant couvert par le voile (le contenu de la fenêtre)
     * @param passThrough    composant qui doit rester cliquable malgré le voile
     * @param restoreFocusTo composant qui reprend le focus quand le voile disparaît (peut être null)
     */
    BusyGlassPane(JComponent area, JComponent passThrough, Component restoreFocusTo) {
        this.area = area;
        this.passThrough = passThrough;
        this.restoreFocusTo = restoreFocusTo;
        setOpaque(false);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        // Des écouteurs vides suffisent pour que le voile « absorbe » les événements au lieu de les laisser passer.
        MouseAdapter swallow = new MouseAdapter() { };
        addMouseListener(swallow);
        addMouseMotionListener(swallow);
        addMouseWheelListener(swallow);
        addKeyListener(new KeyAdapter() { });
        timer = new Timer(25, e -> {
            angle = (angle + 12) % 360;
            repaint(spinnerBounds());
        });
    }

    /** Affiche le voile et lance l'animation. */
    void start() {
        angle = 0;
        setVisible(true);
        timer.start();
        requestFocusInWindow();
    }

    /** Masque le voile et arrête l'animation (sans effet s'il est déjà masqué). */
    void stop() {
        timer.stop();
        boolean hadFocus = isFocusOwner();
        setVisible(false);
        if (hadFocus && restoreFocusTo != null) {
            restoreFocusTo.requestFocusInWindow();
        }
    }

    @Override
    public boolean contains(int x, int y) {
        if (!areaBounds().contains(x, y)) {
            return false; // barre de titre décorée, etc. : on laisse passer
        }
        return !passThroughBounds().contains(x, y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color bg = UIManager.getColor("Panel.background");
            if (bg == null) {
                bg = Color.WHITE;
            }
            Rectangle hole = passThroughBounds();
            Area veil = new Area(areaBounds());
            veil.subtract(new Area(new RoundRectangle2D.Double(hole.x, hole.y, hole.width, hole.height, 12, 12)));
            g2.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 130));
            g2.fill(veil);

            Rectangle s = spinnerBounds();
            double inset = STROKE / 2.0 + 1; // +1 : marge de spinnerBounds()
            double d = SPINNER_SIZE - STROKE;
            g2.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Color accent = UIManager.getColor("Component.accentColor");
            if (accent == null) {
                accent = FALLBACK_ACCENT;
            }
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 50));
            g2.draw(new Ellipse2D.Double(s.x + inset, s.y + inset, d, d));
            g2.setColor(accent);
            g2.draw(new Arc2D.Double(s.x + inset, s.y + inset, d, d, -angle, -100, Arc2D.OPEN));
        } finally {
            g2.dispose();
        }
    }

    /** Zone couverte par le voile, en coordonnées du voile. */
    private Rectangle areaBounds() {
        return SwingUtilities.convertRectangle(area, new Rectangle(0, 0, area.getWidth(), area.getHeight()), this);
    }

    private Rectangle passThroughBounds() {
        return SwingUtilities.convertRectangle(passThrough.getParent(), passThrough.getBounds(), this);
    }

    private Rectangle spinnerBounds() {
        Rectangle a = areaBounds();
        // marge de 1 px : le trait arrondi déborde légèrement du cercle théorique
        return new Rectangle(a.x + (a.width - SPINNER_SIZE) / 2 - 1, a.y + (a.height - SPINNER_SIZE) / 2 - 1,
                SPINNER_SIZE + 2, SPINNER_SIZE + 2);
    }
}
