// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title MerchantRegistry
/// @notice Maps terminal ID → merchant Tempo address.
contract MerchantRegistry {
    /// @notice Terminal ID (8 bytes) → merchant address
    mapping(bytes8 => address) public merchants;

    event MerchantRegistered(bytes8 indexed terminalId, address indexed merchant);

    /// @notice Register a terminal → merchant mapping. For PoC: permissionless.
    function registerMerchant(bytes8 terminalId, address merchant) external {
        require(merchant != address(0), "Zero address");
        merchants[terminalId] = merchant;
        emit MerchantRegistered(terminalId, merchant);
    }
}
