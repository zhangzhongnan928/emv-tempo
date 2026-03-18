// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Test.sol";
import "../src/EMVSettlement.sol";
import "../src/CardRegistry.sol";
import "../src/MerchantRegistry.sol";

/// @notice Mock TIP-20 token for testing
contract MockAUDS is ITIP20 {
    mapping(address => uint256) private _balances;
    mapping(address => mapping(address => uint256)) private _allowances;

    function name() external pure returns (string memory) { return "Australian Dollar Stablecoin"; }
    function symbol() external pure returns (string memory) { return "AUDS"; }
    function decimals() external pure returns (uint8) { return 6; }
    function totalSupply() external pure returns (uint256) { return 0; }
    function balanceOf(address account) external view returns (uint256) { return _balances[account]; }

    function transfer(address to, uint256 amount) external returns (bool) {
        _balances[msg.sender] -= amount;
        _balances[to] += amount;
        return true;
    }

    function allowance(address owner, address spender) external view returns (uint256) {
        return _allowances[owner][spender];
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        _allowances[msg.sender][spender] = amount;
        return true;
    }

    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        uint256 allowed = _allowances[from][msg.sender];
        require(allowed >= amount, "Insufficient allowance");
        _allowances[from][msg.sender] = allowed - amount;
        require(_balances[from] >= amount, "Insufficient balance");
        _balances[from] -= amount;
        _balances[to] += amount;
        return true;
    }

    // Test helper: mint tokens
    function mint(address to, uint256 amount) external {
        _balances[to] += amount;
    }
}

contract EMVSettlementTest is Test {
    EMVSettlement public settlement;
    CardRegistry public cardRegistry;
    MerchantRegistry public merchantRegistry;
    MockAUDS public auds;

    bytes8 constant TEST_PAN = bytes8(hex"9510010000000001");
    bytes8 constant TERMINAL_ID = bytes8(hex"5445524D30303100"); // "TERM001\0"
    address constant CARDHOLDER = address(0xBEEF);
    address constant MERCHANT = address(0xCAFE);
    address constant RELAYER = address(0xFEED);

    // Test P-256 key pair (for mock precompile, we don't need real crypto)
    bytes32 constant TEST_PUBKEY_X = bytes32(uint256(0xAABBCCDD));
    bytes32 constant TEST_PUBKEY_Y = bytes32(uint256(0xEEFF0011));
    bytes32 constant TEST_SIG_R = bytes32(uint256(0x1111));
    bytes32 constant TEST_SIG_S = bytes32(uint256(0x2222));

    function setUp() public {
        cardRegistry = new CardRegistry();
        merchantRegistry = new MerchantRegistry();
        auds = new MockAUDS();

        // Deploy mock P-256 precompile that returns 1 for any valid-format input
        // This bytecode: PUSH32(1) PUSH1(0) MSTORE PUSH1(32) PUSH1(0) RETURN
        bytes memory mockP256 = hex"7f000000000000000000000000000000000000000000000000000000000000000160005260206000f3";
        vm.etch(address(0x100), mockP256);

        settlement = new EMVSettlement(
            address(cardRegistry),
            address(merchantRegistry),
            address(auds),
            address(0x100)
        );

        // Register card
        cardRegistry.registerCard(TEST_PAN, TEST_PUBKEY_X, TEST_PUBKEY_Y, CARDHOLDER);

        // Register merchant terminal
        merchantRegistry.registerMerchant(TERMINAL_ID, MERCHANT);

        // Fund cardholder and approve settlement contract
        auds.mint(CARDHOLDER, 1000_000000); // 1000 AUDS (6 decimals)
        vm.prank(CARDHOLDER);
        auds.approve(address(settlement), type(uint256).max);
    }

    /// @notice Build a valid CDOL1 data blob (29 bytes)
    function _buildCDOL1(
        uint256 amountCents,
        bytes2 currency,
        bytes4 unpredictableNumber
    ) internal pure returns (bytes memory) {
        bytes memory cdol1 = new bytes(29);

        // Amount Authorized (6 bytes BCD) - encode 12-digit decimal as BCD
        // e.g. 1000 cents ($10.00) → 000000001000 → 0x00 0x00 0x00 0x00 0x10 0x00
        {
            uint256 val = amountCents;
            for (uint256 i = 6; i > 0; i--) {
                uint8 lo = uint8(val % 10);
                val /= 10;
                uint8 hi = uint8(val % 10);
                val /= 10;
                cdol1[i - 1] = bytes1(hi * 16 + lo);
            }
        }

        // Amount Other (6 bytes) - all zeros
        // cdol1[6..11] already 0

        // Terminal Country Code (2 bytes) - Australia 0036
        cdol1[12] = 0x00;
        cdol1[13] = 0x36;

        // TVR (5 bytes) - all zeros
        // cdol1[14..18] already 0

        // Currency Code (2 bytes)
        cdol1[19] = currency[0];
        cdol1[20] = currency[1];

        // Transaction Date (3 bytes YYMMDD BCD)
        cdol1[21] = 0x26; // 2026
        cdol1[22] = 0x03; // March
        cdol1[23] = 0x18; // 18th

        // Transaction Type (1 byte) - purchase
        cdol1[24] = 0x00;

        // Unpredictable Number (4 bytes)
        cdol1[25] = unpredictableNumber[0];
        cdol1[26] = unpredictableNumber[1];
        cdol1[27] = unpredictableNumber[2];
        cdol1[28] = unpredictableNumber[3];

        return cdol1;
    }

    /// @notice Build CDOL1 for $10.00 AUD
    function _buildDefaultCDOL1() internal pure returns (bytes memory) {
        return _buildCDOL1(1000, bytes2(0x0036), bytes4(0xDEADBEEF));
    }

    // ========== Happy Path ==========

    function test_settle_success() public {
        bytes memory cdol1 = _buildDefaultCDOL1();

        uint256 merchantBalBefore = auds.balanceOf(MERCHANT);
        uint256 cardholderBalBefore = auds.balanceOf(CARDHOLDER);

        // Settle as relayer (anyone can call)
        vm.prank(RELAYER);
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);

        // $10.00 = 1000 cents = 10_000000 in 6-decimal AUDS
        uint256 expectedAmount = 10_000000;
        assertEq(auds.balanceOf(MERCHANT), merchantBalBefore + expectedAmount);
        assertEq(auds.balanceOf(CARDHOLDER), cardholderBalBefore - expectedAmount);
    }

    function test_settle_emitsEvent() public {
        bytes memory cdol1 = _buildDefaultCDOL1();

        vm.expectEmit(true, true, true, true);
        emit EMVSettlement.PaymentSettled(
            TEST_PAN, TERMINAL_ID, MERCHANT, 10_000000, bytes4(0xDEADBEEF)
        );

        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_anyoneCanCall() public {
        bytes memory cdol1 = _buildDefaultCDOL1();

        // Random address submits the transaction
        address randomRelayer = address(0x999);
        vm.prank(randomRelayer);
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);

        // Merchant (not msg.sender) gets paid
        assertEq(auds.balanceOf(MERCHANT), 10_000000);
        assertEq(auds.balanceOf(randomRelayer), 0);
    }

    // ========== Replay Protection ==========

    function test_settle_revertReplay() public {
        bytes memory cdol1 = _buildDefaultCDOL1();
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);

        vm.expectRevert("Already settled");
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_differentUNNotReplay() public {
        bytes memory cdol1a = _buildCDOL1(1000, bytes2(0x0036), bytes4(0xDEADBEEF));
        bytes memory cdol1b = _buildCDOL1(1000, bytes2(0x0036), bytes4(0xCAFEBABE));

        settlement.settle(cdol1a, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
        // Different UN should not revert
        settlement.settle(cdol1b, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);

        assertEq(auds.balanceOf(MERCHANT), 20_000000); // $10 + $10
    }

    // ========== Validation Errors ==========

    function test_settle_revertInvalidLength() public {
        bytes memory badCdol1 = new bytes(28); // Too short
        vm.expectRevert("Invalid CDOL1 length");
        settlement.settle(badCdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_revertWrongCurrency() public {
        // 0x0840 = USD
        bytes memory cdol1 = _buildCDOL1(1000, bytes2(0x0840), bytes4(0xDEADBEEF));
        vm.expectRevert("Not AUD");
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_revertZeroAmount() public {
        bytes memory cdol1 = _buildCDOL1(0, bytes2(0x0036), bytes4(0xDEADBEEF));
        vm.expectRevert("Zero amount");
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_revertUnregisteredCard() public {
        bytes memory cdol1 = _buildDefaultCDOL1();
        bytes8 unknownPan = bytes8(hex"0000000000000002");
        vm.expectRevert("Card not registered");
        settlement.settle(cdol1, unknownPan, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_revertUnregisteredTerminal() public {
        bytes memory cdol1 = _buildDefaultCDOL1();
        bytes8 unknownTerminal = bytes8(hex"0000000000000002");
        vm.expectRevert("Terminal not registered");
        settlement.settle(cdol1, TEST_PAN, unknownTerminal, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_revertInvalidSignature() public {
        // Deploy a mock P-256 verifier that returns 0 (invalid)
        bytes memory mockP256Invalid = hex"7f000000000000000000000000000000000000000000000000000000000000000060005260206000f3";
        vm.etch(address(0x100), mockP256Invalid);

        bytes memory cdol1 = _buildDefaultCDOL1();
        vm.expectRevert("Invalid signature");
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_revertDeactivatedCard() public {
        // Deactivate the card
        vm.prank(CARDHOLDER);
        cardRegistry.deactivateCard(TEST_PAN);

        bytes memory cdol1 = _buildDefaultCDOL1();
        vm.expectRevert("Card not registered");
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_revertInsufficientBalance() public {
        // Drain cardholder balance
        vm.prank(CARDHOLDER);
        auds.transfer(address(0xDEAD), 1000_000000);

        bytes memory cdol1 = _buildDefaultCDOL1();
        vm.expectRevert("Insufficient balance");
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    function test_settle_revertInsufficientAllowance() public {
        // Revoke allowance
        vm.prank(CARDHOLDER);
        auds.approve(address(settlement), 0);

        bytes memory cdol1 = _buildDefaultCDOL1();
        vm.expectRevert("Insufficient allowance");
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
    }

    // ========== BCD Amount Parsing ==========

    function test_parseAmount_10dollars() public {
        // $10.00 = BCD 000000001000
        bytes memory cdol1 = _buildDefaultCDOL1();
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
        // 10.00 * 10^4 = 10_000000 (6 decimal AUDS)
        assertEq(auds.balanceOf(MERCHANT), 10_000000);
    }

    function test_parseAmount_1cent() public {
        // $0.01 = 1 cent
        bytes memory cdol1 = _buildCDOL1(1, bytes2(0x0036), bytes4(0xAAAABBBB));
        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
        // 0.01 * 10^6 = 10000
        assertEq(auds.balanceOf(MERCHANT), 10000);
    }

    function test_parseAmount_largeAmount() public {
        // $999.99 = 99999 cents
        // BCD: 00 00 00 09 99 99
        bytes memory cdol1 = new bytes(29);
        cdol1[3] = 0x09;
        cdol1[4] = 0x99;
        cdol1[5] = 0x99;
        // Currency AUD
        cdol1[19] = 0x00;
        cdol1[20] = 0x36;
        // Date
        cdol1[21] = 0x26;
        cdol1[22] = 0x03;
        cdol1[23] = 0x18;
        // UN
        cdol1[25] = 0xCC;
        cdol1[26] = 0xCC;
        cdol1[27] = 0xCC;
        cdol1[28] = 0xCC;
        // Country code
        cdol1[12] = 0x00;
        cdol1[13] = 0x36;

        settlement.settle(cdol1, TEST_PAN, TERMINAL_ID, TEST_SIG_R, TEST_SIG_S);
        // 999.99 * 10^4 = 999_990000
        assertEq(auds.balanceOf(MERCHANT), 999_990000);
    }
}
