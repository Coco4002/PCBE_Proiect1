package bursa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BursaGresitaDeadlock implements InterfataBursa {
    private final List<Ordin> cereri = new ArrayList<>();
    private final List<Ordin> oferte = new ArrayList<>();
    private final Map<String, Object> lockuriClienti = new ConcurrentHashMap<>();

    private Object getLock(String client) {
        return lockuriClienti.computeIfAbsent(client, k -> new Object());
    }

    @Override
    public void plaseazaOrdin(Ordin ordin) {
        Object lockInitiator = getLock(ordin.getClient());

        // Firul 1 blocheaza mai intai contul clientului sau
        synchronized (lockInitiator) {
            List<Ordin> listaOpusa = (ordin.getTip() == Ordin.Tip.BUY) ? oferte : cereri;
            List<Ordin> listaProprie = (ordin.getTip() == Ordin.Tip.BUY) ? cereri : oferte;

            for (int i = 0; i < listaOpusa.size(); i++) {
                Ordin existent = listaOpusa.get(i);

                if (existent.getCompanie().equals(ordin.getCompanie())
                        && existent.getPret() == ordin.getPret()
                        && !existent.getClient().equals(ordin.getClient())) {

                    Object lockPartener = getLock(existent.getClient());

                    // Pauza scurta deliberata pentru a facilita intersectia firelor -> genereaza DEADLOCK
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException ignored) {}

                    // DEADLOCK: Firul A (are lock pe A) incearca sa ia lock pe B
                    // In paralel, Firul B (are lock pe B) incearca sa ia lock pe A
                    synchronized (lockPartener) {
                        int cantitate = Math.min(ordin.getCantitate(), existent.getCantitate());
                        System.out.printf("[TRANZACTIE DEADLOCK-RISK] %d actiuni %s @ %d lei | %s <-> %s%n",
                                cantitate, ordin.getCompanie(), ordin.getPret(),
                                ordin.getClient(), existent.getClient());

                        ordin.setCantitate(ordin.getCantitate() - cantitate);
                        existent.setCantitate(existent.getCantitate() - cantitate);

                        if (existent.getCantitate() == 0) {
                            listaOpusa.remove(i);
                            i--;
                        }
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
    }

    @Override
    public void afiseazaStareFinala() {
        System.out.println("Carnet final (Deadlock) - Cereri: " + cereri.size() + ", Oferte: " + oferte.size());
    }
}