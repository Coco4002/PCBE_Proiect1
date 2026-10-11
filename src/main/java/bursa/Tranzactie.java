package bursa;

public record Tranzactie(int numar, int ordinCumparare, int ordinVanzare,
                         String cumparator, String vanzator, String companie,
                         int pret, int cantitate) {
    @Override
    public String toString() {
        return String.format("[TRANZACTIE #%d] %d actiuni %s @ %d lei | %s (BUY #%d) <- %s (SELL #%d)",
                numar, cantitate, companie, pret, cumparator, ordinCumparare, vanzator, ordinVanzare);
    }
}
