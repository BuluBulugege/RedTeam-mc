package com.redblue.red.hider;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModHider {

    private static final Logger LOG = LoggerFactory.getLogger("ModHider");

    public static void hide(String modId) {
        ModList modList = ModList.get();
        if (modList == null) {
            LOG.warn("ModList not available yet");
            return;
        }
        try { removeFromIndexedMods(modList, modId); }
        catch (Exception e) { LOG.error("removeFromIndexedMods failed", e); }

        try { removeFromModFiles(modList, modId); }
        catch (Exception e) { LOG.error("removeFromModFiles failed", e); }

        try { removeFromSortedList(modList, modId); }
        catch (Exception e) { LOG.error("removeFromSortedList failed", e); }

        try { removeFromFileById(modList, modId); }
        catch (Exception e) { LOG.error("removeFromFileById failed", e); }

        try { removeFromScanData(modList, modId); }
        catch (Exception e) { LOG.error("removeFromScanData failed", e); }

        LOG.info("ModHider completed for: {}", modId);
    }

    private static void removeFromIndexedMods(ModList modList, String modId) throws Exception {
        Field indexedField = ModList.class.getDeclaredField("indexedMods");
        indexedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ModContainer> indexed = (Map<String, ModContainer>) indexedField.get(modList);
        if (indexed.containsKey(modId)) {
            ConcurrentHashMap<String, ModContainer> mutable = new ConcurrentHashMap<>(indexed);
            mutable.remove(modId);
            indexedField.set(modList, mutable);
            LOG.debug("Removed from indexedMods");
        }
    }

    private static void removeFromModFiles(ModList modList, String modId) throws Exception {
        for (String fieldName : new String[]{"modFiles", "mods"}) {
            try {
                Field field = ModList.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(modList);
                if (value instanceof List<?>) {
                    // 用可变副本替换不可变列表
                    java.util.ArrayList<Object> mutable = new java.util.ArrayList<>((List<?>) value);
                    mutable.removeIf(item -> matchesModId(item, modId));
                    field.set(modList, mutable);
                    LOG.debug("Cleaned field: {}", fieldName);
                }
            } catch (NoSuchFieldException ignored) {}
        }
    }

    private static void removeFromSortedList(ModList modList, String modId) throws Exception {
        // sortedList 是 List<IModInfo>（getMods() 的来源！）
        // sortedContainers 是 List<ModContainer>
        // 都用 matchesModId 统一匹配
        for (String fieldName : new String[]{"sortedContainers", "sortedList"}) {
            try {
                Field field = ModList.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(modList);
                if (value instanceof List<?>) {
                    java.util.ArrayList<Object> mutable = new java.util.ArrayList<>((List<?>) value);
                    mutable.removeIf(item -> matchesModId(item, modId));
                    field.set(modList, mutable);
                    LOG.debug("Cleaned field: {}", fieldName);
                }
            } catch (NoSuchFieldException ignored) {}
        }
    }

    /**
     * 从 fileById Map 中移除（getModFileById 的来源）
     */
    private static void removeFromFileById(ModList modList, String modId) throws Exception {
        Field field = ModList.class.getDeclaredField("fileById");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) field.get(modList);
        if (map.containsKey(modId)) {
            java.util.HashMap<String, Object> mutable = new java.util.HashMap<>(map);
            mutable.remove(modId);
            field.set(modList, mutable);
            LOG.debug("Cleaned fileById");
        }
    }

    /**
     * 从 modFileScanData 中移除（防止扫描数据泄露 mod 信息）
     */
    private static void removeFromScanData(ModList modList, String modId) throws Exception {
        Field field = ModList.class.getDeclaredField("modFileScanData");
        field.setAccessible(true);
        Object value = field.get(modList);
        if (value instanceof List<?>) {
            java.util.ArrayList<Object> mutable = new java.util.ArrayList<>((List<?>) value);
            mutable.removeIf(item -> {
                try {
                    // ModFileScanData 关联 IModFile，通过 getModInfos() 获取 mod 列表
                    java.lang.reflect.Method getInfos = item.getClass().getMethod("getIModInfoData");
                    Object infos = getInfos.invoke(item);
                    if (infos instanceof List<?>) {
                        return ((List<?>) infos).stream().anyMatch(i -> matchesModId(i, modId));
                    }
                } catch (Exception e) {
                    // 尝试备用方法
                    try {
                        String s = item.toString();
                        return s.contains(modId);
                    } catch (Exception ignored) {}
                }
                return false;
            });
            field.set(modList, mutable);
            LOG.debug("Cleaned modFileScanData");
        }
    }

    private static boolean matchesModId(Object item, String modId) {
        if (item instanceof IModFileInfo) {
            return ((IModFileInfo) item).getMods().stream()
                    .anyMatch(info -> info.getModId().equals(modId));
        }
        if (item instanceof IModInfo) {
            return ((IModInfo) item).getModId().equals(modId);
        }
        if (item instanceof ModContainer) {
            return ((ModContainer) item).getModId().equals(modId);
        }
        return false;
    }
}
