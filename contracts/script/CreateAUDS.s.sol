// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Script.sol";

/// @notice TIP-20 Factory interface (minimal)
interface ITIP20Factory {
    function createToken(
        string calldata name,
        string calldata symbol,
        string calldata currency,
        uint8 decimals
    ) external returns (address);
}

/// @notice Create AUDS TIP-20 token via the Tempo TIP-20 Factory.
/// Usage: forge script script/CreateAUDS.s.sol --rpc-url tempo_testnet --broadcast
contract CreateAUDSScript is Script {
    address constant TIP20_FACTORY = 0x20Fc000000000000000000000000000000000000;

    function run() external {
        uint256 deployerKey = vm.envUint("PRIVATE_KEY");

        vm.startBroadcast(deployerKey);

        ITIP20Factory factory = ITIP20Factory(TIP20_FACTORY);
        address auds = factory.createToken(
            "Australian Dollar Stablecoin",
            "AUDS",
            "AUD",
            6
        );

        console.log("AUDS token deployed at:", auds);

        vm.stopBroadcast();
    }
}
