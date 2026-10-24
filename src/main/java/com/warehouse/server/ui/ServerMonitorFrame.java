package com.warehouse.server.ui;

import com.warehouse.common.rmi.RmiNames;

import javax.swing.*;
import java.awt.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;

public final class ServerMonitorFrame extends JFrame {
    private final String serverId;
    private final String host;
    private final int registryPort;
    private final int servicePort;
    private final String peerHost;
    private final int peerPort;
    private final JLabel peerStatus = new JLabel("Đang kiểm tra...");
    private final JLabel pendingStatus = new JLabel("Đang tải...");
    private final JLabel failedStatus = new JLabel("Đang tải...");
    private final JTextArea log = new JTextArea();
    private final Registry peerRegistry;
    private final DataSource dataSource;

    public ServerMonitorFrame(String serverId, String host, int registryPort, int servicePort,
                              String peerHost, int peerPort, Registry peerRegistry, DataSource dataSource) {
        super("Server Monitor - " + serverId);
        this.serverId = serverId;
        this.host = host;
        this.registryPort = registryPort;
        this.servicePort = servicePort;
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.peerRegistry = peerRegistry;
        this.dataSource = dataSource;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(850, 560);
        setLocationByPlatform(true);
        buildUi();
        refreshStatus();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        JLabel title = new JLabel("Giám sát " + serverId);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("MariaDB cục bộ và các dịch vụ RMI đang hoạt động");
        JPanel header = new JPanel(new BorderLayout(0, 5));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);

        JPanel info = new JPanel(new GridLayout(2, 4, 12, 12));
        info.add(card("Địa chỉ", host));
        info.add(card("RMI Registry", String.valueOf(registryPort)));
        info.add(card("RMI Service", String.valueOf(servicePort)));
        info.add(card("MariaDB", "Đã kết nối"));
        info.add(card("Peer Server", peerStatus));
        info.add(card("Điều phối khóa", "server-a".equalsIgnoreCase(serverId) ? "Máy này" : "Server A"));
        info.add(card("Chờ đồng bộ", pendingStatus));
        info.add(card("Đồng bộ lỗi", failedStatus));

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane logPane = new JScrollPane(log);
        logPane.setBorder(BorderFactory.createTitledBorder("Kiểm tra kết nối"));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refresh = new JButton("Làm mới trạng thái");
        JButton stop = new JButton("Dừng Server an toàn");
        actions.add(refresh);
        actions.add(stop);
        refresh.addActionListener(e -> refreshStatus());
        stop.addActionListener(e -> stopServer());
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.add(info, BorderLayout.NORTH);
        content.add(logPane, BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        add(root);
    }

    private void refreshStatus() {
        refreshPeer();
        new SwingWorker<int[], Void>() {
            @Override protected int[] doInBackground() throws Exception {
                int[] counts = new int[2];
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement query = connection.prepareStatement(
                             "SELECT sync_status, COUNT(*) FROM sync_events "
                                     + "WHERE sync_status IN ('PENDING','FAILED') GROUP BY sync_status");
                     ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        if ("PENDING".equals(rows.getString(1))) counts[0] = rows.getInt(2);
                        if ("FAILED".equals(rows.getString(1))) counts[1] = rows.getInt(2);
                    }
                }
                return counts;
            }
            @Override protected void done() {
                try {
                    int[] counts = get();
                    pendingStatus.setText(String.valueOf(counts[0]));
                    failedStatus.setText(String.valueOf(counts[1]));
                } catch (Exception ex) {
                    pendingStatus.setText("Không đọc được");
                    failedStatus.setText("Không đọc được");
                }
            }
        }.execute();
    }

    private JPanel card(String title, String value) {
        JPanel panel = new JPanel(new BorderLayout(3, 5));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 226, 232)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel name = new JLabel(title);
        name.setForeground(new Color(90, 100, 110));
        panel.add(name, BorderLayout.NORTH);
        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(valueLabel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel card(String title, JLabel value) {
        JPanel panel = card(title, "");
        panel.remove(1);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private void refreshPeer() {
        peerStatus.setText("Đang kiểm tra...");
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                if (peerRegistry == null) return false;
                try {
                    peerRegistry.lookup(RmiNames.SYNC);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override protected void done() {
                try {
                    boolean connected = get();
                    peerStatus.setText(connected ? "Đã kết nối" : "Chưa kết nối");
                    peerStatus.setForeground(connected ? new Color(35, 135, 93)
                            : new Color(190, 60, 55));
                    appendLog("Peer Server", connected ? "Đã thấy dịch vụ Sync" : "Chưa thấy dịch vụ Sync",
                            connected ? "OK" : "CHỜ");
                } catch (Exception e) {
                    peerStatus.setText("Không kiểm tra được");
                    appendLog("Peer Server", "Kiểm tra Registry", "LỖI");
                }
            }
        }.execute();
    }

    private void appendLog(String component, String event, String result) {
        log.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " | " + component + " | " + event + " | " + result + System.lineSeparator());
    }

    private void stopServer() {
        if (JOptionPane.showConfirmDialog(this, "Dừng Server và đóng kết nối?", "Xác nhận",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
            dispose();
            System.exit(0);
        }
    }
}
