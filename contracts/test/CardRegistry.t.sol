// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Test.sol";
import "../src/CardRegistry.sol";

contract CardRegistryTest is Test {
    CardRegistry public registry;
    bytes8 constant TEST_PAN = bytes8(hex"9510010000000001");
    bytes32 constant TEST_X = bytes32(uint256(0x1234));
    bytes32 constant TEST_Y = bytes32(uint256(0x5678));
    address constant CARDHOLDER = address(0xBEEF);

    function setUp() public {
        registry = new CardRegistry();
    }

    function test_registerCard() public {
        registry.registerCard(TEST_PAN, TEST_X, TEST_Y, CARDHOLDER);

        CardRegistry.Card memory card = registry.getCard(TEST_PAN);
        assertEq(card.pubKeyX, TEST_X);
        assertEq(card.pubKeyY, TEST_Y);
        assertEq(card.cardholder, CARDHOLDER);
        assertTrue(card.active);
    }

    function test_registerCard_emitsEvent() public {
        vm.expectEmit(true, true, false, true);
        emit CardRegistry.CardRegistered(TEST_PAN, CARDHOLDER);
        registry.registerCard(TEST_PAN, TEST_X, TEST_Y, CARDHOLDER);
    }

    function test_registerCard_revertsDuplicate() public {
        registry.registerCard(TEST_PAN, TEST_X, TEST_Y, CARDHOLDER);
        vm.expectRevert("Card already registered");
        registry.registerCard(TEST_PAN, TEST_X, TEST_Y, CARDHOLDER);
    }

    function test_registerCard_revertsZeroAddress() public {
        vm.expectRevert("Zero address");
        registry.registerCard(TEST_PAN, TEST_X, TEST_Y, address(0));
    }

    function test_deactivateCard() public {
        registry.registerCard(TEST_PAN, TEST_X, TEST_Y, CARDHOLDER);
        vm.prank(CARDHOLDER);
        registry.deactivateCard(TEST_PAN);

        CardRegistry.Card memory card = registry.getCard(TEST_PAN);
        assertFalse(card.active);
    }

    function test_deactivateCard_revertsNotCardholder() public {
        registry.registerCard(TEST_PAN, TEST_X, TEST_Y, CARDHOLDER);
        vm.prank(address(0xDEAD));
        vm.expectRevert("Not cardholder");
        registry.deactivateCard(TEST_PAN);
    }

    function test_deactivateCard_revertsInactive() public {
        vm.expectRevert("Card not active");
        registry.deactivateCard(TEST_PAN);
    }

    function test_getCard_unregistered() public view {
        CardRegistry.Card memory card = registry.getCard(bytes8(hex"0000000000000000"));
        assertFalse(card.active);
        assertEq(card.cardholder, address(0));
    }
}
