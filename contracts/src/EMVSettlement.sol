// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./CardRegistry.sol";
import "./MerchantRegistry.sol";
import "./interfaces/ITIP20.sol";

/// @title EMVSettlement
/// @notice Settles EMV contactless payments on-chain by verifying P-256 signatures
///         via the RIP-7212 precompile and transferring TIP-20 (AUDS) tokens.
contract EMVSettlement {
    /// @notice RIP-7212 P-256 precompile address.
    /// If RIP-7212 is unavailable, set to the Daimo p256-verifier fallback.
    address public immutable p256Verifier;

    CardRegistry public immutable cardRegistry;
    MerchantRegistry public immutable merchantRegistry;
    ITIP20 public immutable auds;

    /// @notice Replay protection: keccak256(pan, unpredictableNumber, txDate) → settled
    mapping(bytes32 => bool) public usedNonces;

    event PaymentSettled(
        bytes8 indexed pan,
        bytes8 indexed terminalId,
        address indexed merchant,
        uint256 amount,
        bytes4 unpredictableNumber
    );

    constructor(
        address _cardRegistry,
        address _merchantRegistry,
        address _auds,
        address _p256Verifier
    ) {
        cardRegistry = CardRegistry(_cardRegistry);
        merchantRegistry = MerchantRegistry(_merchantRegistry);
        auds = ITIP20(_auds);
        p256Verifier = _p256Verifier;
    }

    /// @notice Settle an EMV payment on-chain. Anyone can call this.
    /// @param cdol1Data The raw CDOL1 bytes (29 bytes) the card signed.
    /// @param pan The card PAN (from READ RECORD, not in CDOL1).
    /// @param terminalId The terminal ID (for merchant lookup).
    /// @param sigR P-256 signature R component.
    /// @param sigS P-256 signature S component.
    function settle(
        bytes calldata cdol1Data,
        bytes8 pan,
        bytes8 terminalId,
        bytes32 sigR,
        bytes32 sigS
    ) external {
        // 1. Validate CDOL1 length
        require(cdol1Data.length == 29, "Invalid CDOL1 length");

        // 2. Parse CDOL1 fixed-format fields
        uint256 amount = _parseAmount(cdol1Data[0:6]);          // 9F02: Amount Authorized (6B BCD)
        // cdol1Data[6:12]  = 9F03: Amount Other (skip)
        // cdol1Data[12:14] = 9F1A: Terminal Country Code (skip)
        // cdol1Data[14:19] = 95:   TVR (skip)
        bytes2 currencyCode = bytes2(cdol1Data[19:21]);         // 5F2A: Currency Code
        bytes3 txDate = bytes3(cdol1Data[21:24]);               // 9A:   Transaction Date
        // cdol1Data[24]    = 9C:   Transaction Type (skip)
        bytes4 unpredictableNumber = bytes4(cdol1Data[25:29]);  // 9F37: Unpredictable Number

        // 3. Validate currency (0x0036 = AUD in ISO 4217 numeric)
        require(currencyCode == bytes2(0x0036), "Not AUD");

        // 4. Validate amount is non-zero
        require(amount > 0, "Zero amount");

        // 5. Replay protection
        bytes32 nonceKey = keccak256(abi.encodePacked(pan, unpredictableNumber, txDate));
        require(!usedNonces[nonceKey], "Already settled");
        usedNonces[nonceKey] = true;

        // 6. Reconstruct hash the card signed: SHA-256(CDOL1 data)
        bytes32 hash = sha256(cdol1Data);

        // 7. Verify P-256 signature via RIP-7212 precompile (or Daimo fallback)
        CardRegistry.Card memory card = cardRegistry.getCard(pan);
        require(card.active, "Card not registered");

        (bool ok, bytes memory result) = p256Verifier.staticcall(
            abi.encodePacked(hash, sigR, sigS, card.pubKeyX, card.pubKeyY)
        );
        require(ok && result.length == 32 && uint256(bytes32(result)) == 1, "Invalid signature");

        // 8. Resolve merchant from terminal ID
        address merchant = merchantRegistry.merchants(terminalId);
        require(merchant != address(0), "Terminal not registered");

        // 9. Transfer AUDS from cardholder to merchant
        // Cardholder must have pre-approved this contract via AUDS.approve()
        require(auds.transferFrom(card.cardholder, merchant, amount), "Transfer failed");

        emit PaymentSettled(pan, terminalId, merchant, amount, unpredictableNumber);
    }

    /// @dev Parse BCD-encoded amount (6 bytes) to uint256.
    /// EMV amounts are BCD: 0x000000001000 = $10.00
    /// For 6-decimal AUDS: multiply by 10^4 (BCD has 2 implied decimals).
    function _parseAmount(bytes calldata bcd) internal pure returns (uint256) {
        uint256 result = 0;
        for (uint256 i = 0; i < 6; i++) {
            uint8 b = uint8(bcd[i]);
            result = result * 100 + (b >> 4) * 10 + (b & 0x0F);
        }
        // BCD amount has 2 implicit decimal places (cents)
        // AUDS has 6 decimals → multiply by 10^4
        return result * 10000;
    }
}
