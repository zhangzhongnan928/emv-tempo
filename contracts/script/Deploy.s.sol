// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Script.sol";
import "../src/P256Check.sol";
import "../src/CardRegistry.sol";
import "../src/MerchantRegistry.sol";
import "../src/EMVSettlement.sol";

/// @notice Deploy all EMV settlement contracts to Tempo testnet.
/// Usage: forge script script/Deploy.s.sol --rpc-url tempo_testnet --broadcast
contract DeployScript is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("PRIVATE_KEY");
        address audsToken = vm.envAddress("AUDS_TOKEN");

        vm.startBroadcast(deployerKey);

        // 1. Deploy P256Check and verify RIP-7212 availability
        P256Check p256Check = new P256Check();
        console.log("P256Check deployed at:", address(p256Check));

        bool p256Available;
        try p256Check.isP256Available() returns (bool available) {
            p256Available = available;
        } catch {
            p256Available = false;
        }
        console.log("RIP-7212 available:", p256Available);

        // Use RIP-7212 precompile or Daimo fallback
        address p256Verifier;
        if (p256Available) {
            p256Verifier = address(0x100);
            console.log("Using RIP-7212 precompile at 0x100");
        } else {
            // TODO: Deploy Daimo p256-verifier fallback
            // For now, still use 0x100 - will need to be updated
            p256Verifier = address(0x100);
            console.log("WARNING: RIP-7212 not available, need Daimo fallback");
        }

        // 2. Deploy registries
        CardRegistry cardRegistry = new CardRegistry();
        console.log("CardRegistry deployed at:", address(cardRegistry));

        MerchantRegistry merchantRegistry = new MerchantRegistry();
        console.log("MerchantRegistry deployed at:", address(merchantRegistry));

        // 3. Deploy settlement contract
        EMVSettlement settlement = new EMVSettlement(
            address(cardRegistry),
            address(merchantRegistry),
            audsToken,
            p256Verifier
        );
        console.log("EMVSettlement deployed at:", address(settlement));

        vm.stopBroadcast();
    }
}
