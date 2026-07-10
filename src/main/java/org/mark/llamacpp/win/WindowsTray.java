package org.mark.llamacpp.win;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class WindowsTray {

    private static final WindowsTray INSTANCE = new WindowsTray();

    public static WindowsTray getInstance() {
        return INSTANCE;
    }

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Map<String, JMenuItem> menuItems = new ConcurrentHashMap<>();

    private volatile TrayIcon trayIcon;
    private volatile JDialog popupHost;
    private volatile JPopupMenu popupMenu;
    private volatile Runnable defaultAction;

    static {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // fallback to default
        }
    }

    private WindowsTray() {
    }

    public boolean isSupported() {
        return SystemTray.isSupported();
    }

    public boolean isStarted() {
        return started.get();
    }

    public void start(String tooltip) throws AWTException {
        if (started.get()) {
            return;
        }
        if (!SystemTray.isSupported()) {
            throw new IllegalStateException("System tray is not supported");
        }
        runOnEdt(() -> this.startInternal(tooltip));
    }

    public void stop() {
        runOnEdt(this::stopInternal);
    }

    public void setDefaultAction(Runnable action) {
        this.defaultAction = action;
    }

    public String addButton(String text, Runnable onClick) {
        String id = UUID.randomUUID().toString();
        this.addButton(id, text, onClick);
        return id;
    }

    public void addButton(String id, String text, Runnable onClick) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(onClick, "onClick");
        runOnEdt(() -> this.addButtonInternal(id, text, onClick));
    }

    public String addCheckBoxButton(String text, boolean selected, Runnable onToggle) {
        String id = UUID.randomUUID().toString();
        this.addCheckBoxButton(id, text, selected, onToggle);
        return id;
    }

    public void addCheckBoxButton(String id, String text, boolean selected, Runnable onToggle) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(onToggle, "onToggle");
        runOnEdt(() -> this.addCheckBoxButtonInternal(id, text, selected, onToggle));
    }

    public void setCheckBoxSelected(String id, boolean selected) {
        Objects.requireNonNull(id, "id");
        runOnEdt(() -> {
            JMenuItem item = menuItems.get(id);
            if (item instanceof JCheckBoxMenuItem) {
                ((JCheckBoxMenuItem) item).setState(selected);
            }
        });
    }

    public void removeButton(String id) {
        Objects.requireNonNull(id, "id");
        runOnEdt(() -> this.removeButtonInternal(id));
    }

    public void clearButtons() {
        runOnEdt(this::clearButtonsInternal);
    }

    public void addSeparator() {
        runOnEdt(() -> {
        	this.ensureInitialized();
            this.popupMenu.addSeparator();
        });
    }

    public void displayInfoMessage(String title, String message) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        TrayIcon icon = this.trayIcon;
        if (icon == null) {
            return;
        }
        icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
    }

    private void addButtonInternal(String id, String text, Runnable onClick) {
    	this.ensureInitialized();
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> {
        	this.hidePopup();
            onClick.run();
        });
        JMenuItem previous = menuItems.put(id, item);
        if (previous != null) {
        	this.popupMenu.remove(previous);
        }
        this.popupMenu.add(item);
    }

    private void addCheckBoxButtonInternal(String id, String text, boolean selected, Runnable onToggle) {
    	this.ensureInitialized();
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, selected);
        item.addActionListener(e -> {
        	this.hidePopup();
            boolean newState = item.getState();
            item.setState(newState);
            onToggle.run();
        });
        JMenuItem previous = this.menuItems.put(id, item);
        if (previous != null) {
        	this.popupMenu.remove(previous);
        }
        this.popupMenu.add(item);
    }

    private void hidePopup() {
        JDialog host = this.popupHost;
        if (host != null && host.isVisible()) {
            host.setVisible(false);
        }
    }

    private void removeButtonInternal(String id) {
        ensureInitialized();
        JMenuItem item = this.menuItems.remove(id);
        if (item == null) {
            return;
        }
        this.popupMenu.remove(item);
    }

    private void clearButtonsInternal() {
    	this.ensureInitialized();
        this.popupMenu.removeAll();
        this.menuItems.clear();
    }

    private void startInternal(String tooltip) {
        if (!this.started.compareAndSet(false, true)) {
            return;
        }
        this.ensureInitialized();

        Image iconImage;
        try {
            iconImage = loadTrayImage("/icon/icon.png");
        } catch (IOException e) {
        	this.started.set(false);
            throw new IllegalStateException("Failed to load tray icon: /icon/icon.png", e);
        }

        TrayIcon newTrayIcon = new TrayIcon(iconImage, tooltip);
        newTrayIcon.setImageAutoSize(true);
        newTrayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopupAt(e.getXOnScreen(), e.getYOnScreen());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopupAt(e.getXOnScreen(), e.getYOnScreen());
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() >= 2) {
                    Runnable action = defaultAction;
                    if (action != null) {
                        SwingUtilities.invokeLater(action);
                    }
                }
            }
        });

        try {
            SystemTray.getSystemTray().add(newTrayIcon);
            this.trayIcon = newTrayIcon;
        } catch (AWTException e) {
        	this.started.set(false);
            throw new RuntimeException(e);
        }
    }

    private void stopInternal() {
        TrayIcon icon = this.trayIcon;
        if (icon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon);
        }
        this.trayIcon = null;
        this.started.set(false);

        JDialog host = this.popupHost;
        if (host != null) {
            host.setVisible(false);
            host.dispose();
        }
        this.popupHost = null;
        this.popupMenu = null;
        this.menuItems.clear();
    }

    private void ensureInitialized() {
        if (this.popupMenu != null && this.popupHost != null) {
            return;
        }
        this.popupMenu = new JPopupMenu();

        JDialog host = new JDialog();
        host.setUndecorated(true);
        host.setFocusableWindowState(true);
        host.setAlwaysOnTop(true);
        host.setContentPane(new javax.swing.JPanel());
        host.getContentPane().setLayout(new BorderLayout());

        host.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                host.setVisible(false);
            }
        });

        this.popupHost = host;
    }

    private void showPopupAt(int rawX, int rawY) {
        ensureInitialized();
        JDialog host = this.popupHost;
        JPopupMenu menu = this.popupMenu;
        if (host == null || menu == null) {
            return;
        }

        Point adjusted = adjustDpi(rawX, rawY);
        Dimension menuSize = menu.getPreferredSize();
        Point location = clampToScreen(adjusted.x, adjusted.y, menuSize);

        host.setLocation(location);
        host.setSize(menuSize.width + 4, menuSize.height + 4);
        host.setVisible(true);
        host.toFront();
        host.requestFocusInWindow();
        menu.show(host.getContentPane(), 2, 2);
    }

    private static Point adjustDpi(int rawX, int rawY) {
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        Point mousePoint = pointerInfo == null ? null : pointerInfo.getLocation();
        if (mousePoint == null) {
            return new Point(rawX, rawY);
        }

        GraphicsConfiguration gc = findGraphicsConfiguration(mousePoint.x, mousePoint.y);
        AffineTransform tx = gc.getDefaultTransform();
        double sx = tx.getScaleX();
        double sy = tx.getScaleY();
        if (sx <= 0 || sy <= 0) {
            return mousePoint;
        }

        Point eventAsUser = new Point((int) Math.round(rawX / sx), (int) Math.round(rawY / sy));
        double dUser = mousePoint.distance(eventAsUser);
        double dRaw = mousePoint.distance(rawX, rawY);

        return dUser <= dRaw ? eventAsUser : new Point(rawX, rawY);
    }

    private static Point clampToScreen(int x, int y, Dimension size) {
        int w = size == null ? 0 : size.width;
        int h = size == null ? 0 : size.height;

        GraphicsConfiguration gc = findGraphicsConfiguration(x, y);
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

        int usableX = bounds.x + insets.left;
        int usableY = bounds.y + insets.top;
        int usableW = bounds.width - insets.left - insets.right;
        int usableH = bounds.height - insets.top - insets.bottom;

        x = Math.max(x, usableX);
        y = Math.max(y, usableY);

        if (w > 0) {
            x = Math.min(x, usableX + usableW - w);
        }
        if (h > 0) {
            y = Math.min(y, usableY + usableH - h);
        }

        return new Point(x, y);
    }

    private static GraphicsConfiguration findGraphicsConfiguration(int x, int y) {
        Point p = new Point(x, y);
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice device : ge.getScreenDevices()) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            if (gc.getBounds().contains(p)) {
                return gc;
            }
        }
        return ge.getDefaultScreenDevice().getDefaultConfiguration();
    }

    private Image loadTrayImage(String resourcePath) throws IOException {
        URL url = WindowsTray.class.getResource(resourcePath);
        if (url == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        BufferedImage image = ImageIO.read(url);
        Dimension traySize = SystemTray.getSystemTray().getTrayIconSize();
        if (traySize.width <= 0 || traySize.height <= 0) {
            return image;
        }
        return image.getScaledInstance(traySize.width, traySize.height, Image.SCALE_SMOOTH);
    }

    private static void runOnEdt(Runnable task) {
        if (EventQueue.isDispatchThread()) {
            task.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
