// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Test.sol";
import "../src/MerchantRegistry.sol";

contract MerchantRegistryTest is Test {
    MerchantRegistry public registry;
    bytes8 constant TERMINAL_ID = bytes8(hex"5445524D303031"); // "TERM001\0"
    address constant MERCHANT = address(0xCAFE);

    function setUp() public {
        registry = new MerchantRegistry();
    }

    function test_registerMerchant() public {
        registry.registerMerchant(TERMINAL_ID, MERCHANT);
        assertEq(registry.merchants(TERMINAL_ID), MERCHANT);
    }

    function test_registerMerchant_emitsEvent() public {
        vm.expectEmit(true, true, false, true);
        emit MerchantRegistry.MerchantRegistered(TERMINAL_ID, MERCHANT);
        registry.registerMerchant(TERMINAL_ID, MERCHANT);
    }

    function test_registerMerchant_revertsZeroAddress() public {
        vm.expectRevert("Zero address");
        registry.registerMerchant(TERMINAL_ID, address(0));
    }

    function test_registerMerchant_allowsUpdate() public {
        registry.registerMerchant(TERMINAL_ID, MERCHANT);
        address newMerchant = address(0xFACE);
        registry.registerMerchant(TERMINAL_ID, newMerchant);
        assertEq(registry.merchants(TERMINAL_ID), newMerchant);
    }

    function test_unregisteredTerminal() public view {
        assertEq(registry.merchants(bytes8(hex"0000000000000000")), address(0));
    }
}
