package net.hanasashimi.client

import net.fabricmc.api.ClientModInitializer

object MarkovJuniorMCClient : ClientModInitializer {
	override fun onInitializeClient() {
		MarkovBuildController.initialize()
	}
}