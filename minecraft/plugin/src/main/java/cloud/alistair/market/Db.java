package cloud.alistair.market;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite storage. Every method runs on the main server thread, so there are no races between
 * two players spending the same money.
 */
public final class Db implements AutoCloseable {
    public record Holding(String symbol, long micros, long costCents) {}

    public record Listing(long id, UUID seller, String sellerName, byte[] item, long priceCents) {}

    private final Connection conn;

    public Db(File file) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS balances (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, cents INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS holdings (uuid TEXT NOT NULL, symbol TEXT NOT NULL, micros INTEGER NOT NULL,"
                    + " cost_cents INTEGER NOT NULL, PRIMARY KEY (uuid, symbol))");
            s.execute("CREATE TABLE IF NOT EXISTS listings (id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT NOT NULL,"
                    + " seller_name TEXT NOT NULL, item BLOB NOT NULL, price_cents INTEGER NOT NULL, created INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS supply (material TEXT PRIMARY KEY, pressure REAL NOT NULL, updated INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS ledger (ts INTEGER NOT NULL, uuid TEXT NOT NULL, kind TEXT NOT NULL,"
                    + " detail TEXT NOT NULL, cents INTEGER NOT NULL)");
        }
    }

    // --- balances ---

    public long balance(UUID id) {
        try (PreparedStatement p = conn.prepareStatement("SELECT cents FROM balances WHERE uuid = ?")) {
            p.setString(1, id.toString());
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean hasAccount(UUID id) {
        try (PreparedStatement p = conn.prepareStatement("SELECT 1 FROM balances WHERE uuid = ?")) {
            p.setString(1, id.toString());
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Adds {@code delta} (may be negative) and records why. Caller checks the balance first. */
    public void add(UUID id, String name, long delta, String kind, String detail) {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO balances (uuid, name, cents) VALUES (?, ?, ?)"
                        + " ON CONFLICT(uuid) DO UPDATE SET cents = cents + excluded.cents, name = excluded.name")) {
            p.setString(1, id.toString());
            p.setString(2, name);
            p.setLong(3, delta);
            p.executeUpdate();
            log(id, kind, detail, delta);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void set(UUID id, String name, long cents) {
        add(id, name, cents - balance(id), "admin-set", Money.format(cents));
    }

    /** uuid -> [name, cents] for everyone who has ever had money. */
    public Map<UUID, Map.Entry<String, Long>> allBalances() {
        Map<UUID, Map.Entry<String, Long>> out = new LinkedHashMap<>();
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("SELECT uuid, name, cents FROM balances")) {
            while (r.next()) out.put(UUID.fromString(r.getString(1)), Map.entry(r.getString(2), r.getLong(3)));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // --- holdings ---

    public List<Holding> holdings(UUID id) {
        List<Holding> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT symbol, micros, cost_cents FROM holdings WHERE uuid = ? ORDER BY symbol")) {
            p.setString(1, id.toString());
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new Holding(r.getString(1), r.getLong(2), r.getLong(3)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Optional<Holding> holding(UUID id, String symbol) {
        return holdings(id).stream().filter(h -> h.symbol().equals(symbol)).findFirst();
    }

    /** uuid -> holdings, for the leaderboard. */
    public Map<UUID, List<Holding>> allHoldings() {
        Map<UUID, List<Holding>> out = new LinkedHashMap<>();
        try (Statement s = conn.createStatement();
                ResultSet r = s.executeQuery("SELECT uuid, symbol, micros, cost_cents FROM holdings")) {
            while (r.next()) {
                out.computeIfAbsent(UUID.fromString(r.getString(1)), k -> new ArrayList<>())
                        .add(new Holding(r.getString(2), r.getLong(3), r.getLong(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void putHolding(UUID id, String symbol, long micros, long costCents) {
        try {
            if (micros <= 0) {
                try (PreparedStatement p = conn.prepareStatement("DELETE FROM holdings WHERE uuid = ? AND symbol = ?")) {
                    p.setString(1, id.toString());
                    p.setString(2, symbol);
                    p.executeUpdate();
                }
                return;
            }
            try (PreparedStatement p = conn.prepareStatement(
                    "INSERT INTO holdings (uuid, symbol, micros, cost_cents) VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT(uuid, symbol) DO UPDATE SET micros = excluded.micros, cost_cents = excluded.cost_cents")) {
                p.setString(1, id.toString());
                p.setString(2, symbol);
                p.setLong(3, micros);
                p.setLong(4, costCents);
                p.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- shop supply ---

    /** How many cents of {@code material} have been sold recently, after decay. */
    public double pressure(String material, double halfLifeHours) {
        try (PreparedStatement p = conn.prepareStatement("SELECT pressure, updated FROM supply WHERE material = ?")) {
            p.setString(1, material);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return 0;
                double hours = (System.currentTimeMillis() - r.getLong(2)) / 3_600_000.0;
                return r.getDouble(1) * Math.pow(0.5, hours / halfLifeHours);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void addPressure(String material, double cents, double halfLifeHours) {
        double now = pressure(material, halfLifeHours) + cents;
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO supply (material, pressure, updated) VALUES (?, ?, ?)"
                        + " ON CONFLICT(material) DO UPDATE SET pressure = excluded.pressure, updated = excluded.updated")) {
            p.setString(1, material);
            p.setDouble(2, now);
            p.setLong(3, System.currentTimeMillis());
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- listings ---

    public long addListing(UUID seller, String sellerName, byte[] item, long priceCents) {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO listings (seller, seller_name, item, price_cents, created) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, seller.toString());
            p.setString(2, sellerName);
            p.setBytes(3, item);
            p.setLong(4, priceCents);
            p.setLong(5, System.currentTimeMillis());
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                r.next();
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Listing> listings(UUID sellerOrNull) {
        String sql = "SELECT id, seller, seller_name, item, price_cents FROM listings"
                + (sellerOrNull == null ? "" : " WHERE seller = ?") + " ORDER BY id DESC";
        List<Listing> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement(sql)) {
            if (sellerOrNull != null) p.setString(1, sellerOrNull.toString());
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.add(new Listing(r.getLong(1), UUID.fromString(r.getString(2)), r.getString(3),
                            r.getBytes(4), r.getLong(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Optional<Listing> listing(long id) {
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT id, seller, seller_name, item, price_cents FROM listings WHERE id = ?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Listing(r.getLong(1), UUID.fromString(r.getString(2)), r.getString(3),
                        r.getBytes(4), r.getLong(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void removeListing(long id) {
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM listings WHERE id = ?")) {
            p.setLong(1, id);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void log(UUID id, String kind, String detail, long cents) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("INSERT INTO ledger (ts, uuid, kind, detail, cents) VALUES (?, ?, ?, ?, ?)")) {
            p.setLong(1, System.currentTimeMillis());
            p.setString(2, id.toString());
            p.setString(3, kind);
            p.setString(4, detail);
            p.setLong(5, cents);
            p.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
