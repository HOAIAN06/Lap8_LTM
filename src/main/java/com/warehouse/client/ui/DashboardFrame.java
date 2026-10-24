package com.warehouse.client.ui;

import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.common.dto.LockLease;
import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class DashboardFrame extends JFrame {
    private static final Color BACKGROUND = new Color(245, 248, 251);
    private static final Color WHITE = Color.WHITE;
    private static final Color INK = new Color(27, 47, 68);
    private static final Color MUTED = new Color(112, 132, 152);
    private static final Color BORDER = new Color(211, 223, 232);
    private static final Color TEAL = new Color(15, 139, 132);
    private final WarehouseClient client;
    private final String clientId;
    private final String username;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Mã SP", "Tên sản phẩm", "Danh mục", "Giá", "Tồn kho"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField(20);
    private final JLabel status = new JLabel("Sẵn sàng");
    private final JLabel peerStatus = new JLabel("Server còn lại: đang kiểm tra...");
    private final JLabel productTitle = new JLabel("Chưa chọn sản phẩm");
    private final JLabel productDetails = new JLabel("Chọn một dòng trong bảng.");
    private final JButton editButton = new AppButton("Sửa sản phẩm", false);
    private final JButton importButton = new AppButton("Nhập kho", false);
    private final JButton exportButton = new AppButton("Xuất kho", false);
    private final JButton deleteButton = new AppButton("Xóa sản phẩm", false);
    private final JButton historyButton = new AppButton("Lịch sử nhập / xuất", false);
    private final JButton addButton = new AppButton("+ Thêm sản phẩm", true);
    private final Timer heartbeatTimer;
    private List<Product> products = List.of();
    private LockLease currentLease;
    private UUID activeWriteProductId;
    private boolean busy;
    private boolean heartbeatRunning;

    public DashboardFrame(WarehouseClient client, String clientId, String username) {
        super("Quản lý kho - " + username);
        this.client = client;
        this.clientId = clientId;
        this.username = username;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                handleWindowClosing();
            }
        });
        setMinimumSize(new Dimension(980, 620));
        setSize(1180, 700);
        setLocationRelativeTo(null);
        buildUi();
        heartbeatTimer = new Timer(10_000, e -> renewLease());
        heartbeatTimer.start();
        loadProducts();
        refreshPeer();
    }

    private void handleWindowClosing() {
        heartbeatTimer.stop();
        LockLease lease = currentLease;
        currentLease = null;
        setVisible(false);
        if (lease != null) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    client.locks().release(lease.lockToken(), clientId);
                } catch (Exception ignored) {
                    // Lease will automatically expire on server via TTL if network is lost
                }
            });
            try {
                future.get(1500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Timeout or interruption: proceed with closing safely without hanging Swing EDT
            }
        }
        dispose();
        System.exit(0);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(24, 26, 16, 26));
        JLabel title = new JLabel("Quản lý kho hàng");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
        title.setForeground(INK);
        JPanel heading = new JPanel(new BorderLayout(0, 5));
        heading.setOpaque(false);
        heading.add(title, BorderLayout.NORTH);
        JLabel identity = new JLabel("Nhân viên " + username + "   ·   Client " + clientId);
        identity.setForeground(MUTED);
        heading.add(identity, BorderLayout.SOUTH);
        root.add(heading, BorderLayout.NORTH);

        table.setRowHeight(40);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(new Color(224, 244, 241));
        table.setSelectionForeground(INK);
        table.setFont(table.getFont().deriveFont(13f));
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final NumberFormat prices = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));

            @Override public Component getTableCellRendererComponent(JTable grid, Object value,
                    boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(grid, value, selected, focused, row, column);
                if (column == 3 && value instanceof BigDecimal price) setText(prices.format(price));
                setBackground(selected ? grid.getSelectionBackground()
                        : row % 2 == 0 ? WHITE : new Color(250, 252, 253));
                setForeground(INK);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
                setHorizontalAlignment(column >= 3 ? SwingConstants.RIGHT : SwingConstants.LEFT);
                return this;
            }
        });
        table.getTableHeader().setPreferredSize(new Dimension(0, 42));
        table.getTableHeader().setBackground(new Color(239, 245, 248));
        table.getTableHeader().setForeground(INK);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD, 12f));
        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(245);
        table.getColumnModel().getColumn(2).setPreferredWidth(145);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        table.getColumnModel().getColumn(4).setPreferredWidth(85);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateSelection();
        });
        JPanel searchBar = new JPanel(new BorderLayout(12, 0));
        searchBar.setOpaque(false);
        searchBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        JButton find = new AppButton("Tìm", false);
        JButton refresh = new AppButton("Làm mới", false);
        search.setFont(search.getFont().deriveFont(14f));
        search.setForeground(INK);
        search.setBackground(WHITE);
        search.setPreferredSize(new Dimension(230, 40));
        search.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(7, 11, 7, 11)));
        JPanel searchControls = new JPanel(new BorderLayout(8, 0));
        searchControls.setOpaque(false);
        JLabel searchLabel = new JLabel("Mã hoặc tên");
        searchLabel.setForeground(MUTED);
        searchControls.add(searchLabel, BorderLayout.WEST);
        searchControls.add(search, BorderLayout.CENTER);
        JPanel searchButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        searchButtons.setOpaque(false);
        searchButtons.add(find);
        searchButtons.add(refresh);
        searchControls.add(searchButtons, BorderLayout.EAST);
        searchBar.add(searchControls, BorderLayout.CENTER);
        searchBar.add(addButton, BorderLayout.EAST);
        search.addActionListener(e -> loadProducts());
        find.addActionListener(e -> loadProducts());
        refresh.addActionListener(e -> { search.setText(""); loadProducts(); refreshPeer(); });
        addButton.addActionListener(e -> createProduct());
        JPanel list = card();
        list.setLayout(new BorderLayout(0, 0));
        list.add(searchBar, BorderLayout.NORTH);
        JScrollPane tablePane = new JScrollPane(table);
        tablePane.setBorder(BorderFactory.createLineBorder(BORDER));
        tablePane.getViewport().setBackground(WHITE);
        list.add(tablePane, BorderLayout.CENTER);

        JPanel actions = card();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.setPreferredSize(new Dimension(282, 0));
        JLabel sectionTitle = new JLabel("SẢN PHẨM ĐÃ CHỌN");
        sectionTitle.setFont(sectionTitle.getFont().deriveFont(Font.BOLD, 11f));
        sectionTitle.setForeground(MUTED);
        sectionTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.add(sectionTitle);
        actions.add(Box.createVerticalStrut(14));
        productTitle.setFont(productTitle.getFont().deriveFont(Font.BOLD, 17f));
        productTitle.setForeground(INK);
        productDetails.setForeground(MUTED);
        for (JComponent component : new JComponent[]{productTitle, productDetails}) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            actions.add(component);
            actions.add(Box.createVerticalStrut(8));
        }
        actions.add(Box.createVerticalStrut(8));
        actions.add(new JSeparator());
        actions.add(Box.createVerticalStrut(20));
        for (JButton button : new JButton[]{editButton, importButton,
                exportButton, historyButton, deleteButton}) {
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            actions.add(button);
            actions.add(Box.createVerticalStrut(10));
        }
        editButton.addActionListener(e -> editProduct());
        importButton.addActionListener(e -> changeInventory(true));
        exportButton.addActionListener(e -> changeInventory(false));
        historyButton.addActionListener(e -> showHistory());
        deleteButton.addActionListener(e -> deleteProduct());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, actions);
        split.setResizeWeight(1.0);
        split.setDividerLocation(790);
        split.setDividerSize(16);
        split.setBorder(null);
        split.setOpaque(false);
        split.setUI(new BasicSplitPaneUI() {
            @Override public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics graphics) {
                        graphics.setColor(BACKGROUND);
                        graphics.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
            }
        });
        root.add(split, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        status.setForeground(MUTED);
        peerStatus.setForeground(MUTED);
        footer.add(status, BorderLayout.WEST);
        footer.add(peerStatus, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        updateSelection();
    }

    private static JPanel card() {
        JPanel panel = new JPanel();
        panel.setBackground(WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)));
        return panel;
    }

    private void loadProducts() {
        String term = search.getText().trim();
        status.setText("Đang tải danh sách sản phẩm...");
        new SwingWorker<List<Product>, Void>() {
            @Override protected List<Product> doInBackground() throws Exception {
                return client.warehouse().listProducts(term);
            }
            @Override protected void done() {
                try {
                    UUID selected = selectedProductId();
                    products = get();
                    model.setRowCount(0);
                    for (Product product : products) {
                        model.addRow(new Object[]{product.productCode(), product.productName(),
                                product.category(), product.price(), product.quantity()});
                    }
                    if (selected != null) {
                        for (int i = 0; i < products.size(); i++) {
                            if (products.get(i).id().equals(selected)) {
                                int view = table.convertRowIndexToView(i);
                                if (view >= 0) table.setRowSelectionInterval(view, view);
                                break;
                            }
                        }
                    }
                    status.setText(products.isEmpty() ?
                            (term.isEmpty() ? "Chưa có sản phẩm trong kho." : "Không có sản phẩm phù hợp.") :
                            "Đã tải " + products.size() + " sản phẩm.");
                    updateSelection();
                } catch (Exception ex) { showError(ex); }
            }
        }.execute();
    }

    private void refreshPeer() {
        peerStatus.setText("Server còn lại: đang kiểm tra...");
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() throws Exception {
                return client.warehouse().isPeerAvailable();
            }
            @Override protected void done() {
                try {
                    boolean available = get();
                    peerStatus.setText(available ? "Server còn lại: đã kết nối" : "Server còn lại: chưa kết nối");
                    peerStatus.setForeground(available ? new Color(33, 116, 79) : new Color(166, 95, 27));
                } catch (Exception ex) { peerStatus.setText("Server còn lại: không kiểm tra được"); }
            }
        }.execute();
    }

    private void renewLease() {
        LockLease lease = currentLease;
        if (lease == null || heartbeatRunning) return;
        heartbeatRunning = true;
        new SwingWorker<LockLease, Void>() {
            @Override protected LockLease doInBackground() throws Exception {
                return client.locks().heartbeat(lease.lockToken(), clientId);
            }
            @Override protected void done() {
                heartbeatRunning = false;
                if (currentLease != lease) return;
                try { currentLease = get(); updateSelection(); }
                catch (Exception ex) {
                    currentLease = null;
                    activeWriteProductId = null;
                    status.setText("Phiên thao tác đã hết hạn hoặc mất kết nối. Vui lòng thử lại.");
                    updateSelection();
                }
            }
        }.execute();
    }

    private void createProduct() {
        ProductInput input = productInput("Thêm sản phẩm", null);
        if (input == null) return;
        runRemote("Đang thêm sản phẩm...", () -> client.warehouse().createProduct(input, clientId, username),
                product -> { status.setText("Đã thêm " + product.productCode() + "."); loadProducts(); });
    }

    private void editProduct() {
        Product product = selectedProduct();
        withWriteLock(product, lease -> {
            ProductInput input = productInput("Sửa " + product.productCode(), product);
            if (input == null) {
                releaseAfterCancel(lease);
                return;
            }
            runWrite("Đang lưu sản phẩm...", lease,
                    () -> client.warehouse().updateProduct(product.id(), input,
                            lease.lockToken(), lease.fencingToken(), clientId, username),
                    updated -> { status.setText("Đã lưu " + updated.productCode() + "."); loadProducts(); });
        });
    }

    private void changeInventory(boolean importing) {
        Product product = selectedProduct();
        withWriteLock(product, lease -> {
            String value = JOptionPane.showInputDialog(this,
                    (importing ? "Nhập kho" : "Xuất kho") + " - " + product.productCode()
                            + "\nTồn kho hiện tại: " + product.quantity() + "\nSố lượng:");
            if (value == null) {
                releaseAfterCancel(lease);
                return;
            }
            int quantity;
            try {
                quantity = Integer.parseInt(value.trim());
                if (quantity <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Nhập số nguyên lớn hơn 0.", "Số lượng không hợp lệ",
                        JOptionPane.WARNING_MESSAGE);
                releaseAfterCancel(lease);
                return;
            }
            if (!importing && quantity > product.quantity()) {
                JOptionPane.showMessageDialog(this, "Số lượng xuất vượt tồn kho hiện tại.",
                        "Không thể xuất kho", JOptionPane.WARNING_MESSAGE);
                releaseAfterCancel(lease);
                return;
            }
            runWrite(importing ? "Đang nhập kho..." : "Đang xuất kho...", lease,
                    () -> client.warehouse().changeInventory(product.id(), quantity, importing,
                            lease.lockToken(), lease.fencingToken(), clientId, username),
                    transaction -> { status.setText("Tồn kho mới: " + transaction.quantityAfter() + "."); loadProducts(); });
        });
    }

    private void deleteProduct() {
        Product product = selectedProduct();
        withWriteLock(product, lease -> {
            if (JOptionPane.showConfirmDialog(this, "Xóa sản phẩm " + product.productCode() + "?",
                    "Xác nhận xóa mềm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                    != JOptionPane.YES_OPTION) {
                releaseAfterCancel(lease);
                return;
            }
            runWrite("Đang xóa sản phẩm...", lease, () -> {
                client.warehouse().deleteProduct(product.id(), lease.lockToken(), lease.fencingToken(), clientId, username);
                return null;
            }, ignored -> { status.setText("Đã xóa " + product.productCode() + "."); loadProducts(); });
        });
    }

    private void showHistory() {
        Product product = selectedProduct();
        if (product == null) return;
        runRemote("Đang tải lịch sử...", () -> client.warehouse().transactionHistory(product.id()),
                rows -> showHistoryDialog(product, rows));
    }

    private void showHistoryDialog(Product product, List<InventoryTransaction> rows) {
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Sản phẩm này chưa có giao dịch nhập/xuất.",
                    "Lịch sử " + product.productCode(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        DefaultTableModel history = new DefaultTableModel(
                new Object[]{"Mã giao dịch", "Loại", "Số lượng", "Trước", "Sau", "Người làm", "Thời gian"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        for (InventoryTransaction tx : rows) {
            history.addRow(new Object[]{tx.transactionCode(), tx.transactionType(), tx.quantity(),
                    tx.quantityBefore(), tx.quantityAfter(), tx.createdBy(), formatter.format(tx.createdAt())});
        }
        JTable historyTable = new JTable(history);
        historyTable.setRowHeight(28);
        JScrollPane pane = new JScrollPane(historyTable);
        pane.setPreferredSize(new Dimension(850, 350));
        JOptionPane.showMessageDialog(this, pane, "Lịch sử " + product.productCode(),
                JOptionPane.PLAIN_MESSAGE);
    }

    private ProductInput productInput(String title, Product existing) {
        JTextField code = new JTextField(existing == null ? "" : existing.productCode(), 20);
        code.setEditable(existing == null);
        JTextField name = new JTextField(existing == null ? "" : existing.productName(), 20);
        JTextField category = new JTextField(existing == null ? "" : existing.category(), 20);
        JTextField price = new JTextField(existing == null ? "" : existing.price().toPlainString(), 20);
        JPanel fields = new JPanel(new GridLayout(0, 2, 10, 10));
        fields.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        fields.add(new JLabel("Mã sản phẩm:")); fields.add(code);
        fields.add(new JLabel("Tên sản phẩm:")); fields.add(name);
        fields.add(new JLabel("Danh mục:")); fields.add(category);
        fields.add(new JLabel("Giá:")); fields.add(price);
        while (JOptionPane.showConfirmDialog(this, fields, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                String productCode = code.getText().trim();
                String productName = name.getText().trim();
                String productCategory = category.getText().trim();
                BigDecimal productPrice = new BigDecimal(price.getText().trim());
                if (productCode.isEmpty() || productName.isEmpty() || productCategory.isEmpty()
                        || productPrice.signum() < 0) throw new IllegalArgumentException();
                return new ProductInput(productCode, productName, productCategory, productPrice);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, "Điền đủ mã, tên, danh mục và giá không âm.",
                        "Dữ liệu không hợp lệ", JOptionPane.WARNING_MESSAGE);
            }
        }
        return null;
    }

    private Product selectedProduct() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? null : products.get(table.convertRowIndexToModel(viewRow));
    }

    private UUID selectedProductId() {
        Product product = selectedProduct();
        return product == null ? null : product.id();
    }

    private void withWriteLock(Product product, java.util.function.Consumer<LockLease> action) {
        if (product == null || busy || activeWriteProductId != null) return;
        if (currentLease != null && !currentLease.resourceId().equals(product.id())) {
            JOptionPane.showMessageDialog(this, "Đang có một thao tác khác chưa hoàn tất.",
                    "Không thể thực hiện", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (currentLease != null) {
            activeWriteProductId = product.id();
            updateSelection();
            action.accept(currentLease);
            return;
        }
        busy = true;
        status.setText("Đang kiểm tra sản phẩm " + product.productCode() + "...");
        updateSelection();
        new SwingWorker<LockLease, Void>() {
            @Override protected LockLease doInBackground() throws Exception {
                return client.locks().acquire(product.id(), clientId);
            }
            @Override protected void done() {
                busy = false;
                try {
                    currentLease = get();
                    activeWriteProductId = product.id();
                    updateSelection();
                    action.accept(currentLease);
                } catch (Exception ex) {
                    showError(ex);
                    updateSelection();
                }
            }
        }.execute();
    }

    private void releaseAfterCancel(LockLease lease) {
        busy = true;
        status.setText("Đang hoàn tất thao tác...");
        updateSelection();
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return releaseLease(lease);
            }
            @Override protected void done() {
                busy = false;
                clearLease(lease);
                status.setText(Boolean.TRUE.equals(getQuietly())
                        ? "Đã hủy thao tác."
                        : "Đã hủy thao tác; phiên sẽ tự hết hạn sau một thời gian.");
                updateSelection();
            }

            private Boolean getQuietly() {
                try {
                    return get();
                } catch (Exception ex) {
                    return false;
                }
            }
        }.execute();
    }

    private <T> void runWrite(String message, LockLease lease, RemoteCall<T> call,
                              java.util.function.Consumer<T> onSuccess) {
        busy = true;
        status.setText(message);
        updateSelection();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return call.execute();
            }
            @Override protected void done() {
                busy = false;
                try {
                    T result = get();
                    boolean released = releaseLease(lease);
                    clearLease(lease);
                    onSuccess.accept(result);
                    if (!released) {
                        status.setText(status.getText() + " Khóa sẽ tự hết hạn sau một thời gian.");
                    }
                } catch (Exception ex) {
                    showError(ex);
                    activeWriteProductId = null;
                    updateSelection();
                }
            }

        }.execute();
    }

    private boolean releaseLease(LockLease lease) {
        try {
            client.locks().release(lease.lockToken(), clientId);
            return true;
        } catch (Exception ex) {
            status.setText("Thao tác đã hoàn tất nhưng chưa giải phóng được phiên thao tác.");
            return false;
        }
    }

    private void clearLease(LockLease lease) {
        if (currentLease == lease) currentLease = null;
        if (activeWriteProductId != null
                && (lease == null || activeWriteProductId.equals(lease.resourceId()))) {
            activeWriteProductId = null;
        }
    }

    private void updateSelection() {
        Product product = selectedProduct();
        productTitle.setText(product == null ? "Chưa chọn sản phẩm" :
                "<html><div style='width:220px'>" + escapeHtml(product.productCode())
                        + "<br>" + escapeHtml(product.productName()) + "</div></html>");
        productDetails.setText(product == null ? "Chọn một dòng trong bảng." :
                "<html>Tồn kho: " + product.quantity() + "<br>Giá: "
                        + NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
                                .format(product.price()) + "</html>");
        boolean selected = product != null;
        boolean canWrite = !busy && activeWriteProductId == null && selected
                && (currentLease == null || currentLease.resourceId().equals(product.id()));
        addButton.setEnabled(!busy && activeWriteProductId == null);
        editButton.setEnabled(canWrite);
        importButton.setEnabled(canWrite);
        exportButton.setEnabled(canWrite);
        deleteButton.setEnabled(canWrite);
        historyButton.setEnabled(!busy && selected);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private <T> void runRemote(String message, RemoteCall<T> call, java.util.function.Consumer<T> onSuccess) {
        if (busy) return;
        busy = true;
        status.setText(message);
        updateSelection();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return call.execute(); }
            @Override protected void done() {
                busy = false;
                try { onSuccess.accept(get()); }
                catch (Exception ex) { showError(ex); }
                updateSelection();
            }
        }.execute();
    }

    private void showError(Exception exception) {
        Throwable cause = exception;
        while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
        status.setText("Thao tác thất bại: " + cause.getMessage());
        JOptionPane.showMessageDialog(this, cause.getMessage(), "Không thể thực hiện", JOptionPane.ERROR_MESSAGE);
    }

    public LockLease currentLease() {
        return currentLease;
    }

    public void setCurrentLeaseForTest(LockLease lease) {
        this.currentLease = lease;
        updateSelection();
    }

    public Timer heartbeatTimer() {
        return heartbeatTimer;
    }

    @FunctionalInterface private interface RemoteCall<T> { T execute() throws Exception; }
}
