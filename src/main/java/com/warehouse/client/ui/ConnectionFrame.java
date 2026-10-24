package com.warehouse.client.ui;

import com.warehouse.client.config.ClientConfig;
import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.common.network.NetworkAddress;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

public final class ConnectionFrame extends JFrame {
    private final ClientConfig config;
    private final Path configPath;
    private final JTextField host = new JTextField(20);
    private final JTextField port = new JTextField(6);
    private final JLabel status = new JLabel("Chưa kết nối tới Server.");
    private final JPanel buttons = new JPanel();

    public ConnectionFrame(ClientConfig config, Path configPath) {
        super("Kết nối " + config.clientId());
        this.config = config;
        this.configPath = configPath;
        host.setText(config.serverHost());
        port.setText(String.valueOf(config.registryPort()));
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(540, 290));
        JPanel fields = new JPanel(new GridLayout(0, 2, 10, 10));
        fields.setBorder(BorderFactory.createTitledBorder("Thông tin máy chủ"));
        fields.add(new JLabel("IPv4 của Server:"));
        fields.add(host);
        fields.add(new JLabel("RMI Registry port:"));
        fields.add(port);
        JButton test = new JButton("1. Kiểm tra kết nối");
        JButton connect = new JButton("2. Kết nối và đăng nhập");
        connect.setPreferredSize(new Dimension(210, 36));
        buttons.add(test);
        buttons.add(connect);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        JLabel title = new JLabel("Kết nối máy chủ kho");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        JPanel north = new JPanel(new BorderLayout(0, 5));
        north.add(title, BorderLayout.NORTH);
        north.add(new JLabel("Client " + config.clientId() + "  •  Nhập IP Server rồi kết nối."), BorderLayout.SOUTH);
        JPanel header = new JPanel(new BorderLayout());
        header.add(north, BorderLayout.NORTH);
        header.add(status, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);
        fields.setBorder(BorderFactory.createCompoundBorder(fields.getBorder(),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        root.add(fields, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        status.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        status.setForeground(new Color(80, 80, 80));
        add(root);
        getRootPane().setDefaultButton(connect);
        test.addActionListener(e -> connect(false));
        connect.addActionListener(e -> connect(true));
        pack();
        setLocationRelativeTo(null);
    }

    private void connect(boolean openDashboard) {
        String address = host.getText().trim();
        int registryPort;
        try {
            registryPort = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            status.setText("Port RMI không hợp lệ.");
            return;
        }
        if (registryPort < 1 || registryPort > 65535) {
            status.setText("Port RMI phải nằm trong khoảng 1–65535.");
            return;
        }
        if (!NetworkAddress.isIpv4(address)) {
            status.setText("IP Server phải là IPv4 hợp lệ.");
            return;
        }
        setButtonsEnabled(false);
        status.setText("Đang kiểm tra " + address + ":" + registryPort + "...");
        new SwingWorker<WarehouseClient, Void>() {
            @Override
            protected WarehouseClient doInBackground() throws Exception {
                return WarehouseClient.connect(address, registryPort);
            }

            @Override
            protected void done() {
                setButtonsEnabled(true);
                try {
                    WarehouseClient client = get();
                    saveLastConnection(address, registryPort);
                    status.setText("Đã kết nối RMI tới " + address + ":" + registryPort + ".");
                    if (openDashboard) {
                        dispose();
                        new LoginFrame(client, config.clientId(), config.username()).setVisible(true);
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    status.setText("Không thể kết nối Server: " + cause.getMessage());
                    JOptionPane.showMessageDialog(ConnectionFrame.this, status.getText(), "Lỗi kết nối",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void saveLastConnection(String address, int registryPort) {
        try {
            Properties properties = new Properties();
            try (var input = java.nio.file.Files.newInputStream(configPath)) {
                properties.load(input);
            }
            properties.setProperty("server.host", address);
            properties.setProperty("rmi.registry.port", String.valueOf(registryPort));
            NetworkAddress.saveProperties(configPath, properties);
        } catch (IOException e) {
            status.setText("Đã kết nối nhưng không lưu được IP gần nhất: " + e.getMessage());
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        for (Component component : buttons.getComponents()) {
            component.setEnabled(enabled);
        }
    }
}
