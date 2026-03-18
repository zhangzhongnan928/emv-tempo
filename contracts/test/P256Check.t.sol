// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Test.sol";
import "../src/P256Check.sol";

contract P256CheckTest is Test {
    P256Check public checker;

    function setUp() public {
        checker = new P256Check();
    }

    /// @notice Test that verifyP256 returns true for a valid signature
    /// when we etch a mock precompile at 0x100.
    function test_verifyP256_withMockPrecompile() public {
        // Deploy a mock at 0x100 that always returns 1
        bytes memory mockCode = hex"7f000000000000000000000000000000000000000000000000000000000000000160005260206000f3";
        vm.etch(address(0x100), mockCode);

        bytes32 hash = bytes32(uint256(1));
        bytes32 r = bytes32(uint256(2));
        bytes32 s = bytes32(uint256(3));
        bytes32 x = bytes32(uint256(4));
        bytes32 y = bytes32(uint256(5));

        bool available = checker.verifyP256(hash, r, s, x, y);
        assertTrue(available);
    }

    /// @notice Test that verifyP256 returns false when precompile returns 0.
    function test_verifyP256_invalidSig() public {
        // Deploy a mock at 0x100 that returns 0
        bytes memory mockCode = hex"7f000000000000000000000000000000000000000000000000000000000000000060005260206000f3";
        vm.etch(address(0x100), mockCode);

        bytes32 hash = bytes32(uint256(1));
        bool available = checker.verifyP256(hash, bytes32(uint256(2)), bytes32(uint256(3)), bytes32(uint256(4)), bytes32(uint256(5)));
        assertFalse(available);
    }
}
