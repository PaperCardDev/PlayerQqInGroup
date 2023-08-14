package cn.paper_card.player_qq_in_group;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface PlayerQqInGroupApi {
    record Info(
            long qq,
            boolean inGroup
    ) {
    }

    interface QqGroupAccess {
        boolean hasMember(long qq) throws Exception;
    }

    @SuppressWarnings("unused")
    boolean addOrUpdateByQq(long qq, boolean inGroup) throws Exception;

    @SuppressWarnings("unused")
    @Nullable Info queryByQq(long qq) throws Exception;

    @SuppressWarnings("unused")
    void onMemberJoinGroup(long qq);

    @SuppressWarnings("unused")
    void onMemberQuitGroup(long qq);

    @SuppressWarnings("unused")
    void onPreLoginCheck(@NotNull AsyncPlayerPreLoginEvent event, long qq, long groupId, @Nullable QqGroupAccess qqGroupAccess);
}
