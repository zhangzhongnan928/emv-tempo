// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Script.sol";
import "../src/CardRegistry.sol";

/// @notice Register a test card in CardRegistry.
/// Usage: forge script script/RegisterCard.s.sol --rpc-url tempo_testnet --broadcast
contract RegisterCardScript is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("PRIVATE_KEY");
        address cardRegistryAddr = vm.envAddress("CARD_REGISTRY");
        bytes8 pan = bytes8(vm.envBytes32("CARD_PAN"));
        bytes32 pubKeyX = vm.envBytes32("CARD_PUBKEY_X");
        bytes32 pubKeyY = vm.envBytes32("CARD_PUBKEY_Y");
        address cardholder = vm.envAddress("CARDHOLDER_ADDRESS");

        vm.startBroadcast(deployerKey);

        CardRegistry registry = CardRegistry(cardRegistryAddr);
        registry.registerCard(pan, pubKeyX, pubKeyY, cardholder);

        console.log("Card registered for PAN (first 4 bytes):");
        console.logBytes8(pan);
        console.log("Cardholder:", cardholder);

        vm.stopBroadcast();
    }
}
