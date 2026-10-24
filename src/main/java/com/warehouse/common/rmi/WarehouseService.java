package com.warehouse.common.rmi;

import com.warehouse.common.dto.LoginResult;
import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.dto.RegistrationResult;
import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;
import com.warehouse.common.exception.WarehouseException;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.UUID;

public interface WarehouseService extends Remote {
    LoginResult login(String username, String password) throws RemoteException;
    RegistrationResult register(String username, String password, String fullName) throws RemoteException;
    List<Product> listProducts(String search) throws RemoteException;
    Product createProduct(ProductInput input, String clientId, String username) throws RemoteException;
    Product updateProduct(UUID productId, ProductInput input, UUID lockToken,
                          long fencingToken, String clientId, String username)
            throws RemoteException;
    void deleteProduct(UUID productId, UUID lockToken, long fencingToken,
                       String clientId, String username) throws RemoteException;
    InventoryTransaction changeInventory(UUID productId, int quantity, boolean importStock,
                                         UUID lockToken, long fencingToken,
                                         String clientId, String username)
            throws RemoteException;
    List<InventoryTransaction> transactionHistory(UUID productId) throws RemoteException;
    boolean isPeerAvailable() throws RemoteException;
}
