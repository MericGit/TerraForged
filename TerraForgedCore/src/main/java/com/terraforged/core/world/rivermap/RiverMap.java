/*
 *   
 * MIT License
 *
 * Copyright (c) 2020 TerraForged
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.terraforged.core.world.rivermap;

import com.terraforged.core.cell.Cell;
import com.terraforged.core.region.Region;
import com.terraforged.core.util.concurrent.ThreadPool;
import com.terraforged.core.util.concurrent.cache.Cache;
import com.terraforged.core.util.concurrent.cache.CacheEntry;
import com.terraforged.core.world.GeneratorContext;
import com.terraforged.core.world.heightmap.Heightmap;
import com.terraforged.core.world.rivermap.lake.LakeConfig;
import com.terraforged.core.world.rivermap.river.RiverConfig;
import com.terraforged.core.world.rivermap.river.RiverRegion;
import com.terraforged.core.world.terrain.Terrain;
import me.dags.noise.util.NoiseUtil;

import java.util.concurrent.TimeUnit;

public class RiverMap {

    private static final int QUAD_SIZE = (1 << RiverRegion.SCALE) / 2;

    private final Heightmap heightmap;
    private final GeneratorContext context;
    private final RiverMapConfig riverMapConfig;
    private final Cache<CacheEntry<RiverRegion>> cache;

    public RiverMap(Heightmap heightmap, GeneratorContext context) {
        RiverConfig primary = RiverConfig.builder(context.levels)
                .bankHeight(context.settings.rivers.primaryRivers.minBankHeight, context.settings.rivers.primaryRivers.maxBankHeight)
                .bankWidth(context.settings.rivers.primaryRivers.bankWidth)
                .bedWidth(context.settings.rivers.primaryRivers.bedWidth)
                .bedDepth(context.settings.rivers.primaryRivers.bedDepth)
                .fade(context.settings.rivers.primaryRivers.fade)
                .length(2500)
                .main(true)
                .build();
        RiverConfig secondary = RiverConfig.builder(context.levels)
                .bankHeight(context.settings.rivers.secondaryRiver.minBankHeight, context.settings.rivers.secondaryRiver.maxBankHeight)
                .bankWidth(context.settings.rivers.secondaryRiver.bankWidth)
                .bedWidth(context.settings.rivers.secondaryRiver.bedWidth)
                .bedDepth(context.settings.rivers.secondaryRiver.bedDepth)
                .fade(context.settings.rivers.secondaryRiver.fade)
                .length(1000)
                .build();
        RiverConfig tertiary = RiverConfig.builder(context.levels)
                .bankHeight(context.settings.rivers.tertiaryRivers.minBankHeight, context.settings.rivers.tertiaryRivers.maxBankHeight)
                .bankWidth(context.settings.rivers.tertiaryRivers.bankWidth)
                .bedWidth(context.settings.rivers.tertiaryRivers.bedWidth)
                .bedDepth(context.settings.rivers.tertiaryRivers.bedDepth)
                .fade(context.settings.rivers.tertiaryRivers.fade)
                .length(500)
                .build();
        LakeConfig lakes = LakeConfig.of(context.settings.rivers.lake, context.levels);

        this.heightmap = heightmap;
        this.context = context;
        this.riverMapConfig = new RiverMapConfig(context.settings.rivers.riverFrequency, primary, secondary, tertiary, lakes);
        this.cache = new Cache<>(120, 60, TimeUnit.SECONDS);
    }

    public RiverRegionList getRivers(Region region) {
        return getRivers(region.getBlockX(), region.getBlockZ());
    }

    public RiverRegionList getRivers(int blockX, int blockZ) {
        int rx = RiverRegion.blockToRegion(blockX);
        int rz = RiverRegion.blockToRegion(blockZ);

        // check which quarter of the region pos (x,y) is in & get the neighbouring regions' relative coords
        int qx = blockX < RiverRegion.regionToBlock(rx) + QUAD_SIZE ? -1 : 1;
        int qz = blockZ < RiverRegion.regionToBlock(rz) + QUAD_SIZE ? -1 : 1;

        // relative positions of neighbouring regions
        int minX = Math.min(0, qx);
        int minZ = Math.min(0, qz);
        int maxX = Math.max(0, qx);
        int maxZ = Math.max(0, qz);

        RiverRegionList list = new RiverRegionList();
        for (int dz = minZ; dz <= maxZ; dz++) {
            for (int dx = minX; dx <= maxX; dx++) {
                list.add(getRegion(rx + dx, rz + dz));
            }
        }

        return list;
    }

    public void apply(Cell<Terrain> cell, float x, float z) {
        int rx = RiverRegion.blockToRegion((int) x);
        int rz = RiverRegion.blockToRegion((int) z);

        // check which quarter of the region pos (x,y) is in & get the neighbouring regions' relative coords
        int qx = x < RiverRegion.regionToBlock(rx) + QUAD_SIZE ? -1 : 1;
        int qz = z < RiverRegion.regionToBlock(rz) + QUAD_SIZE ? -1 : 1;

        // relative positions of neighbouring regions
        int minX = Math.min(0, qx);
        int minZ = Math.min(0, qz);
        int maxX = Math.max(0, qx);
        int maxZ = Math.max(0, qz);

        // queue up the 4 nearest reiver regions
        int index = 0;
        CacheEntry<RiverRegion>[] entries = new CacheEntry[4];
        for (int dz = minZ; dz <= maxZ; dz++) {
            for (int dx = minX; dx <= maxX; dx++) {
                entries[index++] = getRegion(rx + dx, rz + dz);
            }
        }

        int count = 0;
        while (count < index) {
            for (CacheEntry<RiverRegion> entry : entries) {
                if (entry.isDone()) {
                    count++;
                    entry.get().apply(cell, x, z);
                }
            }
        }
    }

    private CacheEntry<RiverRegion> getRegion(int rx, int rz) {
        long id = NoiseUtil.seed(rx, rz);
        return cache.computeIfAbsent(id, l -> generateRegion(rx, rz));
    }

    private CacheEntry<RiverRegion> generateRegion(int rx, int rz) {
        return CacheEntry.supplyAsync(() -> new RiverRegion(rx, rz, heightmap, context, riverMapConfig), ThreadPool.getPool());
    }
}
