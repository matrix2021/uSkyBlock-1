package us.talabrek.ultimateskyblock.island;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Business logic regarding the calculation of level
 */
public class LevelLogic {
    private static final Pattern KEY_PATTERN = Pattern.compile("(?<id>[0-9]+)(:(?<sub>(\\*|[0-9]+|[0-9]+-[0-9]+)))?");
    private static final int MAX_BLOCK = 255;
    private static final int DATA_BITS = 4;
    private static final int DATA_MASK = 0xf;
    private final FileConfiguration config;

    private final int blockValue[] = new int[MAX_BLOCK<<DATA_BITS];
    private final int blockLimit[] = new int[MAX_BLOCK<<DATA_BITS];
    private final int blockDR[] = new int[MAX_BLOCK<<DATA_BITS];

    public LevelLogic(FileConfiguration config) {
        this.config = config;
        load();
    }

    public void load() {
        int defaultValue = config.getInt("general.default", 10);
        int defaultLimit = config.getInt("general.limit", Integer.MAX_VALUE);
        int defaultDR = config.getInt("general.defaultScale", 10000);
        Arrays.fill(blockValue, defaultValue);
        Arrays.fill(blockLimit, defaultLimit);
        ConfigurationSection blockValueSection = config.getConfigurationSection("blockValues");
        for (String blockKey : blockValueSection.getKeys(false)) {
            int[] blockIds = getBlockIds(blockKey);
            int value = blockValueSection.getInt(blockKey, defaultValue);
            for (int blockId : blockIds) {
                blockValue[blockId] = value;
            }
        }
        ConfigurationSection blockLimitSection = config.getConfigurationSection("blockLimits");
        for (String blockKey : blockLimitSection.getKeys(false)) {
            int[] blockIds = getBlockIds(blockKey);
            int value = blockLimitSection.getInt(blockKey, defaultLimit);
            for (int blockId : blockIds) {
                blockLimit[blockId] = value;
            }
        }
        ConfigurationSection diminishingReturnSection = config.getConfigurationSection("diminishingReturns");
        for (String blockKey : diminishingReturnSection.getKeys(false)) {
            int[] blockIds = getBlockIds(blockKey);
            int value = diminishingReturnSection.getInt(blockKey, defaultDR);
            for (int blockId : blockIds) {
                blockDR[blockId] = value;
            }
        }
    }

    private int[] getBlockIds(String blockKey) {
        Matcher m = KEY_PATTERN.matcher(blockKey);
        if (m.matches()) {
            int blockId = Integer.parseInt(m.group("id"), 10);
            byte[] dataValues = getDataValues(m.group("sub"));
            int[] ids = new int[dataValues.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = blockId << DATA_BITS | (dataValues[i] & 0xff);
            }
            return ids;
        } else {
            uSkyBlock.log(Level.WARNING, "Invalid key '" + blockKey + "' in levelConfig");
        }
        return new int[0];
    }

    private byte[] getDataValues(String sub) {
        if (sub == null || sub.equalsIgnoreCase("*") || sub.equalsIgnoreCase("0-15")) {
            byte[] data = new byte[16];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) ((i) & 0xff);
            }
            return data;
        } else if (!sub.isEmpty()) {
            String[] split = sub.split("-");
            if (split.length == 1) {
                return new byte[] { (byte) (Integer.parseInt(split[0]) & 0xff)};
            } else {
                int min = Integer.parseInt(split[0]);
                int max = Integer.parseInt(split[1]);
                byte[] data = new byte[max-min];
                for (int i = 0; i < data.length; i++) {
                    data[i] = (byte) ((min+i) & 0xff);
                }
                return data;
            }
        }
        return new byte[0];
    }

    public IslandScore calculateScore(PlayerInfo playerInfo) {
        final int radius = Settings.island_protectionRange / 2;
        int pointsPerLevel = config.getInt("general.pointsPerLevel");
        final Location l = playerInfo.getIslandLocation();
        final int px = l.getBlockX();
        final int pz = l.getBlockZ();
        final World w = l.getWorld();
        final int[] values = new int[MAX_BLOCK<<DATA_BITS];
        for (int x = -radius; x <= radius; ++x) {
            for (int y = 0; y <= 255; ++y) {
                for (int z = -radius; z <= radius; ++z) {
                    Block block = w.getBlockAt(px + x, y, pz + z);
                    values[getBlockId(block)] += 1;
                }
            }
        }
        double score = 0;
        List<BlockScore> blocks = new ArrayList<>();
        for (int i = 1<<DATA_BITS; i < MAX_BLOCK<<DATA_BITS; ++i) {
            int count = values[i];
            if (count > 0 && blockValue[i] > 0) {
                BlockScore.State state = BlockScore.State.NORMAL;
                double adjustedCount = count;
                if (count > blockLimit[i] && blockLimit[i] != -1) {
                    adjustedCount = blockLimit[i]; // Hard edge
                    state = BlockScore.State.LIMIT;
                } else if (blockDR[i] > 0 && count > blockDR[i]) {
                    state = BlockScore.State.DIMINISHING;
                    adjustedCount = dReturns(count, blockDR[i]);
                }
                double blockScore = adjustedCount * blockValue[i];
                score += blockScore;
                blocks.add(new BlockScore(new ItemStack(i >> DATA_BITS, 1, (short)(i & DATA_MASK)), count, blockScore/pointsPerLevel, state));
            }
        }
        return new IslandScore(score/pointsPerLevel, blocks);
    }

    private int getBlockId(Block block) {
        return block.getTypeId() << DATA_BITS | (block.getData() & 0xff);
    }

    double dReturns(final double val, final double scale) {
        if (val < 0.0) {
            return -this.dReturns(-val, scale);
        }
        final double mult = val / scale;
        final double trinum = (Math.sqrt(8.0 * mult + 1.0) - 1.0) / 2.0;
        return trinum * scale;
    }

}
