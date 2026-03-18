// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title P256Check
/// @notice Checks whether RIP-7212 P-256 precompile is available on this chain.
contract P256Check {
    address constant P256_VERIFIER = address(0x100);

    /// @notice Test with a known Wycheproof P-256/SHA-256 test vector.
    /// @return true if RIP-7212 precompile is available and working.
    function isP256Available() external view returns (bool) {
        // Wycheproof test vector #1 (tcId 1, "normal")
        // Message hash (SHA-256 of "test"):
        bytes32 hash = 0x532eaabd9574880dbf76b9b8cc00832c20a6ec113d682299550d7a6e0f345e25;
        // Signature (r, s):
        bytes32 r = 0xb292a619339f6e567a305c951c0dcbcc42d16e47f219f9e98e76e09d8770b34a;
        bytes32 s = 0x0177e60492c5a8242f76f07bfe3661bde59ec2a17ce5bd2dab2abebdf89a62e2;
        // Public key (x, y):
        bytes32 pubKeyX = 0x04aaec73635726f213fb8a9e64da3b8632e41495a944d0045b522eba7240fad5;
        bytes32 pubKeyY = 0x87d0f8f4e97cf2a27b8f6325c2e0267b3714edb508c7460a48e535e4b709c8e7;

        (bool success, bytes memory result) = P256_VERIFIER.staticcall(
            abi.encodePacked(hash, r, s, pubKeyX, pubKeyY)
        );
        return success && result.length == 32 && uint256(bytes32(result)) == 1;
    }

    /// @notice Verify an arbitrary P-256 signature.
    function verifyP256(
        bytes32 hash,
        bytes32 sigR,
        bytes32 sigS,
        bytes32 pubKeyX,
        bytes32 pubKeyY
    ) external view returns (bool) {
        (bool success, bytes memory result) = P256_VERIFIER.staticcall(
            abi.encodePacked(hash, sigR, sigS, pubKeyX, pubKeyY)
        );
        return success && result.length == 32 && uint256(bytes32(result)) == 1;
    }
}
