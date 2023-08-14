package cn.paper_card.player_qq_in_group;

import cn.paper_card.database.DatabaseApi;
import cn.paper_card.database.DatabaseConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;

@SuppressWarnings("unused")
public final class PlayerQqInGroup extends JavaPlugin implements PlayerQqInGroupApi {

    private final @NotNull Object connectionLock = new Object();
    private DatabaseConnection connection = null;
    private Table table = null;

    private @NotNull DatabaseConnection getConnection() throws Exception {
        if (this.connection == null) {
            final Plugin database = this.getServer().getPluginManager().getPlugin("Database");
            if (database instanceof final DatabaseApi api) {
                this.connection = api.connectUnimportant();
            } else throw new Exception("Database插件未安装！");
        }
        return this.connection;
    }

    private @NotNull Table getTable() throws Exception {
        if (this.table == null) {
            this.table = new Table(this.getConnection().getConnection());
        }
        return this.table;
    }

    @Override
    public void onEnable() {
        synchronized (this.connectionLock) {
            try {
                this.getTable();
            } catch (Exception e) {
                this.getLogger().severe(e.toString());
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDisable() {
        synchronized (this.connectionLock) {
            if (this.table != null) {
                try {
                    this.table.close();
                } catch (SQLException e) {
                    this.getLogger().severe(e.toString());
                    e.printStackTrace();
                }
                this.table = null;
            }

            if (this.connection != null) {
                try {
                    this.connection.close();
                } catch (SQLException e) {
                    this.getLogger().severe(e.toString());
                    e.printStackTrace();
                }
                this.connection = null;
            }
        }
    }

    @Override
    public boolean addOrUpdateByQq(long qq, boolean inGroup) throws Exception {
        synchronized (this.connectionLock) {
            final Table t = this.getTable();
            final int updated = t.updateByQq(qq, inGroup);
            if (updated == 0) {

                final int inserted = t.insert(qq, inGroup);

                if (inserted != 1) throw new Exception("插入了%d条数据！".formatted(inserted));

                return true;
            }

            if (updated == 1) return false;

            throw new Exception("根据一个QQ更新了%d条数据！".formatted(updated));
        }
    }

    @Override
    public @Nullable Info queryByQq(long qq) throws Exception {
        synchronized (this.connectionLock) {
            final Table t = this.getTable();
            final List<Info> list = t.queryByQq(qq);
            final int size = list.size();
            if (size == 0) return null;
            if (size == 1) return list.get(0);
            throw new Exception("根据一个QQ查询到了多条数据！");
        }
    }

    @Override
    public void onMemberJoinGroup(long qq) {
        try {
            this.addOrUpdateByQq(qq, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onMemberQuitGroup(long qq) {
        try {
            this.addOrUpdateByQq(qq, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void kickNotInGroup(long qq, long groupId, @NotNull AsyncPlayerPreLoginEvent event) {
        event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST);
        event.kickMessage(Component.text()
                .append(Component.text("[你不在我们的群里]").color(NamedTextColor.RED))
                .append(Component.newline())

                .append(Component.text("请加入我们的QQ群："))
                .append(Component.text(groupId).color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.newline())

                .append(Component.text("你的QQ："))
                .append(Component.text(qq).color(NamedTextColor.GRAY).decorate(TextDecoration.BOLD))
                .build());
    }

    private boolean checkWithDatabase(@NotNull AsyncPlayerPreLoginEvent event, long qq, long groupId) {
        final Info info;
        try {
            info = this.queryByQq(qq);
        } catch (Exception e) {
            e.printStackTrace();
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            event.kickMessage(Component.text(e.toString()).color(NamedTextColor.RED));
            return true;
        }

        if (info == null || !info.inGroup()) {
            this.kickNotInGroup(qq, groupId, event);
            return true;
        }
        return false;
    }

    @Override
    public void onPreLoginCheck(@NotNull AsyncPlayerPreLoginEvent event, long qq, long groupId, @Nullable QqGroupAccess qqGroupAccess) {

        if (qqGroupAccess == null) { // 无法访问QQ群，从数据库中查询

            if (this.checkWithDatabase(event, qq, groupId)) return;

        } else { // QQ机器人在线

            final boolean inGroup;

            try {
                inGroup = qqGroupAccess.hasMember(qq);
            } catch (Exception e) {
                e.printStackTrace();
                // 无法通过机器人判断是否在群
                this.getLogger().warning("无法通过机器人判断是否在群");

                if (checkWithDatabase(event, qq, groupId)) return;

                event.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);
                return;
            }

            // 更新数据库信息

            Boolean added = null;

            try {
                added = this.addOrUpdateByQq(qq, inGroup);
            } catch (Exception e) {
                this.getLogger().warning(e.toString());
                e.printStackTrace();
            }

            if (added != null && added) {
                this.getLogger().info("添加数据：玩家[%s]%s群".formatted(
                        event.getName(),
                        (inGroup ? "在" : "不在")
                ));
            }

            if (!inGroup) {
                this.kickNotInGroup(qq, groupId, event);
                return;
            }
        }

        event.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);
    }

    private static class Table {
        private final static String NAME = "qq_in_group";

        private final PreparedStatement statementInsert;
        private final PreparedStatement statementUpdateByQq;

        private final PreparedStatement statementQueryByQq;


        Table(@NotNull Connection connection) throws SQLException {

            this.create(connection);

            try {
                this.statementInsert = connection.prepareStatement
                        ("INSERT INTO %s (qq, state) VALUES (?, ?)".formatted(NAME));

                this.statementUpdateByQq = connection.prepareStatement
                        ("UPDATE %s SET state=? WHERE qq=?".formatted(NAME));

                this.statementQueryByQq = connection.prepareStatement
                        ("SELECT qq, state FROM %s WHERE qq=?".formatted(NAME));


            } catch (SQLException e) {
                try {
                    this.close();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        }

        private void create(@NotNull Connection connection) throws SQLException {
            final String sql = "CREATE TABLE IF NOT EXISTS %s (qq INTEGER NOT NULL, state INTEGER NOT NULL)".formatted(NAME);
            DatabaseConnection.createTable(connection, sql);
        }

        void close() throws SQLException {
            DatabaseConnection.closeAllStatements(this.getClass(), this);
        }

        int insert(long qq, boolean inGroup) throws SQLException {
            final PreparedStatement ps = this.statementInsert;
            ps.setLong(1, qq);
            ps.setInt(2, inGroup ? 1 : 0);
            return ps.executeUpdate();
        }

        int updateByQq(long qq, boolean inGroup) throws SQLException {
            final PreparedStatement ps = this.statementUpdateByQq;
            ps.setLong(1, inGroup ? 1 : 0);
            ps.setLong(2, qq);
            return ps.executeUpdate();
        }

        private @NotNull List<Info> parse(@NotNull ResultSet resultSet) throws SQLException {
            final LinkedList<Info> list = new LinkedList<>();

            try {
                while (resultSet.next()) {
                    final long qq = resultSet.getLong(1);
                    final int inGroup = resultSet.getInt(2);
                    final Info info = new Info(qq, inGroup != 0);
                    list.add(info);
                }
            } catch (SQLException e) {
                try {
                    resultSet.close();
                } catch (SQLException ignored) {
                }
                throw e;
            }


            resultSet.close();

            return list;
        }

        @NotNull List<Info> queryByQq(long qq) throws SQLException {
            final PreparedStatement ps = this.statementQueryByQq;
            ps.setLong(1, qq);
            final ResultSet resultSet = ps.executeQuery();
            return this.parse(resultSet);
        }
    }
}
