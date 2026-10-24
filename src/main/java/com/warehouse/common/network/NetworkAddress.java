package com.warehouse.common.network;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

public final class NetworkAddress {
    private NetworkAddress() {
    }

    public static List<String> localIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // The caller still receives a manual-entry field when enumeration fails.
        }
        List<String> uniqueAddresses = new ArrayList<>(new LinkedHashSet<>(addresses));
        Collections.sort(uniqueAddresses);
        return uniqueAddresses;
    }

    public static String chooseServerAddress(String configured, String role) {
        List<String> addresses = localIpv4Addresses();
        String detected = configured == null || configured.isBlank() || "auto".equalsIgnoreCase(configured)
                ? addresses.isEmpty() ? "" : addresses.get(0) : configured;
        if (GraphicsEnvironment.isHeadless()) {
            if (!isIpv4(detected)) throw new IllegalArgumentException("Configure a valid server.host IPv4 address.");
            return detected;
        }
        JTextField ownAddress = new JTextField(detected, 22);
        ownAddress.setFont(ownAddress.getFont().deriveFont(Font.PLAIN, 14f));
        JComboBox<String> detectedAddresses = new JComboBox<>(addresses.toArray(String[]::new));
        detectedAddresses.setPreferredSize(new Dimension(260, 30));
        detectedAddresses.setSelectedItem(detected);
        detectedAddresses.addActionListener(e -> ownAddress.setText((String) detectedAddresses.getSelectedItem()));
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        JLabel title = new JLabel("Cấu hình mạng cho " + role);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel help = new JLabel("<html>Chọn địa chỉ mà máy khác có thể truy cập. "
                + "Không chọn 127.0.0.1 khi chạy nhiều máy.</html>");
        JPanel heading = new JPanel(new BorderLayout(0, 4));
        heading.add(title, BorderLayout.NORTH);
        heading.add(help, BorderLayout.SOUTH);
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(BorderFactory.createTitledBorder("Địa chỉ RMI"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 6, 5, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridx = 0; c.gridy = 0;
        fields.add(new JLabel("IPv4 LAN phát hiện:"), c);
        c.gridy++;
        fields.add(detectedAddresses, c);
        c.gridy++;
        fields.add(new JLabel("Địa chỉ RMI của máy này:"), c);
        c.gridy++;
        fields.add(ownAddress, c);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);
        while (true) {
            int result = JOptionPane.showConfirmDialog(null, panel, "Thiết lập địa chỉ Server",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                throw new IllegalStateException("Đã hủy cấu hình địa chỉ LAN.");
            }
            if (isIpv4(ownAddress.getText().trim())) return ownAddress.getText().trim();
            JOptionPane.showMessageDialog(null, "Địa chỉ IPv4 không hợp lệ.", "Cấu hình mạng",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    public static String promptPeerAddress(String configured, String role) {
        return promptPeerAddress(configured, role, false);
    }

    public static String promptPeerAddress(String configured, String role, boolean optional) {
        if (GraphicsEnvironment.isHeadless()) {
            String value = configured == null ? "" : configured.trim();
            if (optional && value.isEmpty()) return "";
            if (!isIpv4(value)) throw new IllegalArgumentException("Configure a valid peer.server.host IPv4 address.");
            return value;
        }
        JTextField peer = new JTextField(configured == null ? "" : configured.trim(), 22);
        peer.setFont(peer.getFont().deriveFont(Font.PLAIN, 14f));
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        JLabel title = new JLabel("Kết nối tới " + role);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel help = new JLabel(optional
                ? "<html>Có thể để trống khi chỉ chạy Server A. Muốn đồng bộ, nhập IPv4 của Server B rồi khởi động lại.</html>"
                : "<html>Nhập IPv4 của Server A để dùng bộ điều phối khóa và đồng bộ dữ liệu.</html>");
        JPanel heading = new JPanel(new BorderLayout(0, 4));
        heading.add(title, BorderLayout.NORTH);
        heading.add(help, BorderLayout.SOUTH);
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(BorderFactory.createTitledBorder("Server đối tác"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 6, 5, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridx = 0; c.gridy = 0;
        fields.add(new JLabel("IPv4 Server " + role + " (không dùng localhost):"), c);
        c.gridy++;
        fields.add(peer, c);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);
        while (true) {
            int result = JOptionPane.showConfirmDialog(null, panel, "Thiết lập Server đối tác",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                throw new IllegalStateException("Đã hủy cấu hình IP Server còn lại.");
            }
            if (optional && peer.getText().trim().isEmpty()) return "";
            if (isIpv4(peer.getText().trim())) return peer.getText().trim();
            JOptionPane.showMessageDialog(null, "Địa chỉ IPv4 không hợp lệ.", "Cấu hình mạng",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    public static boolean isIpv4(String value) {
        if (value == null || value.isBlank()) return false;
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                if (part.isBlank() || (part.length() > 1 && part.startsWith("0"))) return false;
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static void saveProperties(Path path, Properties properties) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (var output = Files.newOutputStream(path)) {
            properties.store(output, "Updated by DistributedWarehouseRMI");
        }
    }
}
