// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title CardRegistry
/// @notice Maps card PAN → P-256 public key → cardholder Tempo address.
contract CardRegistry {
    struct Card {
        bytes32 pubKeyX;     // P-256 public key X coordinate
        bytes32 pubKeyY;     // P-256 public key Y coordinate
        address cardholder;  // Tempo address that holds AUDS
        bool active;
    }

    /// @notice PAN (8 bytes, left-padded) → Card
    mapping(bytes8 => Card) public cards;

    event CardRegistered(bytes8 indexed pan, address indexed cardholder);
    event CardDeactivated(bytes8 indexed pan);

    /// @notice Register a card. For PoC: permissionless.
    /// Production: restrict to authorized issuers.
    function registerCard(
        bytes8 pan,
        bytes32 pubKeyX,
        bytes32 pubKeyY,
        address cardholder
    ) external {
        require(!cards[pan].active, "Card already registered");
        require(cardholder != address(0), "Zero address");
        cards[pan] = Card(pubKeyX, pubKeyY, cardholder, true);
        emit CardRegistered(pan, cardholder);
    }

    /// @notice Get card data for a PAN.
    function getCard(bytes8 pan) external view returns (Card memory) {
        return cards[pan];
    }

    /// @notice Deactivate a card (only the cardholder can do this).
    function deactivateCard(bytes8 pan) external {
        require(cards[pan].active, "Card not active");
        require(cards[pan].cardholder == msg.sender, "Not cardholder");
        cards[pan].active = false;
        emit CardDeactivated(pan);
    }
}
