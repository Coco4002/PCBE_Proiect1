package bursa;

public class Ordin {
    public enum Tip {
        BUY,
        SELL
    }

    private final int id;
    private final String client;
    private final String companie;
    private final Tip tip;
    private final int pret;
    private int cantitate;

    public Ordin(int id, String client, String companie, Tip tip, int pret, int cantitate) {
        this.id = id;
        this.client = client;
        this.companie = companie;
        this.tip = tip;
        this.pret = pret;
        this.cantitate = cantitate;
    }

    public int getId() { return id; }
    public String getClient() { return client; }
    public String getCompanie() { return companie; }
    public Tip getTip() { return tip; }
    public int getPret() { return pret; }
    public int getCantitate() { return cantitate; }
    public void setCantitate(int cantitate) { this.cantitate = cantitate; }

    @Override
    public String toString() {
        return tip + " #" + id + " [" + companie + "] " + cantitate + " buc @ " + pret + " lei (Client: " + client + ")";
    }
}