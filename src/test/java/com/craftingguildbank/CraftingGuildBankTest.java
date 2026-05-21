package com.craftingguildbank;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import org.junit.Test;

public class CraftingGuildBankTest
{
    @Test
    public void testPlugin() throws Exception
    {
        ExternalPluginManager.loadBuiltin(CraftingGuildBankPlugin.class);
        RuneLite.main(new String[]{});
    }
}