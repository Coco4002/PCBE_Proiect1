package bursa;

import java.util.ArrayList;
import java.util.List;

public class BursaGresitaRaceCondition implements InterfataBursa {
    private final List<Ordin> cereri = new ArrayList<>();
    private final List<Ordin> oferte = new ArrayList<>();

    @Override
    public void plaseazaOrdin(Ordin ordin) {
        List<Ordin> listaOpusa = (ordin.getTip() == Ordin.Tip.BUY) ? oferte : cereri;
        List<Ordin> listaProprie = (ordin.getTip() == Ordin.Tip.BUY) ? cereri : oferte;

        for (int i = 0; i < listaOpusa.size(); i++) {
            Ordin existent = listaOpusa.get(i);

            if (existent.getCompanie().equals(ordin.getCompanie())
                    && existent.getPret() == ordin.getPret()
                    && !existent.getClient().equals(ordin.getClient())) {

                int cantitate = Math.min(ordin.getCantitate(), existent.getCantitate());
                System.out.printf("[TRANZACTIE RC] %d actiuni %s @ %d lei | %s <-> %s%n",
                        cantitate, ordin.getCompanie(), ordin.getPret(),
                        ordin.getClient(), existent.getClient());

                ordin.setCantitate(ordin.getCantitate() - cantitate);
                existent.setCantitate(existent.getCantitate() - cantitate);

                if (existent.getCantitate() == 0) {
                    listaOpusa.remove(i);
                    i--;
                }

                if (ordin.getCantitate() == 0) {
                    break;
                }
            }
        }

        if (ordin.getCantitate() > 0) {
            listaProprie.add(ordin);
        }
    }

    @Override
    public void afiseazaStareFinala() {
        System.out.println("Carnet final (Race Condition) - Cereri: " + cereri.size() + ", Oferte: " + oferte.size());
    }
}