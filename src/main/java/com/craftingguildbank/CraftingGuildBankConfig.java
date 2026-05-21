package com.craftingguildbank;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("craftingguildbankhelper")
public interface CraftingGuildBankConfig extends Config
{
	@Alpha
	@ConfigItem(
		keyName = "highlightColor",
		name = "Highlight colour",
		description = "Colour of the bank chest preview and highlight",
		position = 1
	)
	default Color highlightColor()
	{
		return Color.CYAN;
	}

	@ConfigItem(
		keyName = "fillHighlight",
		name = "Fill highlight",
		description = "Fill the bank chest preview and highlight",
		position = 2
	)
	default boolean fillHighlight()
	{
		return true;
	}

	@Range(
		min = 0,
		max = 255
	)
	@ConfigItem(
		keyName = "fillOpacity",
		name = "Fill opacity",
		description = "Opacity of the filled highlight area",
		position = 3
	)
	default int fillOpacity()
	{
		return 40;
	}

	@Range(
		min = 1,
		max = 6
	)
	@ConfigItem(
		keyName = "outlineWidth",
		name = "Outline width",
		description = "Width of the highlight outline",
		position = 4
	)
	default int outlineWidth()
	{
		return 2;
	}
}
