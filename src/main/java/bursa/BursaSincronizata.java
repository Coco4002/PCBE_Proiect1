package bursa;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BursaSincronizata implements InterfataBursa {
    public record OrdinVizibil(int id, String client, String companie, Ordin.Tip tip,
                               int pret, int cantitate) {}

    public record StarePiata(List<OrdinVizibil> ordine, List<Tranzactie> istoric) {}

    private final List<Ordin> ordine = new ArrayList<>();
    private final List<Tranzactie> istoric = new ArrayList<>();
    private final Set<Integer> IDsFolosite = ConcurrentHashMap.newKeySet();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final BlockingQueue<Tranzactie> notificari = new LinkedBlockingQueue<>();

    @Override
    public void plaseazaOrdin(Ordin ordin) {
        if (ordin == null) {
            System.err.println("[ORDIN RESPINS] Ordinul nu poate fi null.");
            return;
        }
        
        Ordin copie = new Ordin(ordin.getId(), ordin.getClient(), ordin.getCompanie(),
                ordin.getTip(), ordin.getPret(), ordin.getCantitate());
        if (!valideaza(copie)) {
            return;
        }

        if (!IDsFolosite.add(copie.getId())) {
            System.err.printf("[ORDIN RESPINS] ID de ordin deja folosit: %d.%n", copie.getId());
            return;
        }
        lock.writeLock().lock();
        try {
            matchOrders(copie);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Cantitatea noua reprezinta actiunile ramase de tranzactionat.
    public boolean modificaOrdin(String client, int id, int pret, int cantitate) {
        if (client == null || client.isBlank() || id <= 0) {
            System.err.printf("[MODIFICARE RESPINSA] Ordin #%d: clientul este obligatoriu si ID-ul trebuie sa fie pozitiv.%n", id);
            return false;
        }
        if (pret <= 0 || cantitate <= 0) {
            System.err.printf("[MODIFICARE RESPINSA] Ordin #%d: pretul si cantitatea trebuie sa fie pozitive (pret=%d, cantitate=%d).%n",
                    id, pret, cantitate);
            return false;
        }
        lock.writeLock().lock();
        try {
            for (int i = 0; i < ordine.size(); i++) {
                Ordin vechi = ordine.get(i);
                if (vechi.getId() == id && vechi.getClient().equals(client)) {
                    Ordin nou = new Ordin(id, client, vechi.getCompanie(), vechi.getTip(), pret, cantitate);
                    ordine.remove(i);
                    matchOrders(nou);
                    return true;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        System.err.printf("[MODIFICARE RESPINSA] Ordin #%d: nu exista un ordin deschis cu acest ID pentru clientul %s.%n", id, client);
        return false;
    }

    public StarePiata citestePiata() {
        lock.readLock().lock();
        try {
            List<OrdinVizibil> copii = ordine.stream()
                    .map(o -> new OrdinVizibil(o.getId(), o.getClient(), o.getCompanie(),
                            o.getTip(), o.getPret(), o.getCantitate()))
                    .toList();
            return new StarePiata(copii, List.copyOf(istoric));
        } finally {
            lock.readLock().unlock();
        }
    }

    // Asteptarea observatorului nu tine blocata piata. null inseamna timeout.
    public Tranzactie asteaptaTranzactie(long timeoutMillis) throws InterruptedException {
        return notificari.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public boolean areNotificari() {
        return !notificari.isEmpty();
    }

    @Override
    public void afiseazaStareFinala() {
        StarePiata stare = citestePiata();
        System.out.println("\nOrdine ramase:");
        for (OrdinVizibil ordin : stare.ordine()) {
            System.out.printf("%s #%d [%s] %d buc @ %d lei (Client: %s)%n",
                    ordin.tip(), ordin.id(), ordin.companie(), ordin.cantitate(), ordin.pret(), ordin.client());
        }
        int volum = stare.istoric().stream().mapToInt(Tranzactie::cantitate).sum();
        System.out.printf("Total: %d tranzactii, %d actiuni tranzactionate.%n", stare.istoric().size(), volum);
    }

    // Se apeleaza doar sub lock-ul de scriere, inclusiv la modificarea unui ordin.
    private void matchOrders(Ordin nou) {
        Iterator<Ordin> iterator = ordine.iterator();
        while (nou.getCantitate() > 0 && iterator.hasNext()) {
            Ordin existent = iterator.next();
            if (existent.getTip() == nou.getTip()
                    || !existent.getCompanie().equals(nou.getCompanie())
                    || existent.getPret() != nou.getPret()
                    || existent.getClient().equals(nou.getClient())) {
                continue;
            }

            int cantitate = Math.min(nou.getCantitate(), existent.getCantitate());
            nou.setCantitate(nou.getCantitate() - cantitate);
            existent.setCantitate(existent.getCantitate() - cantitate);
            if (existent.getCantitate() == 0) {
                iterator.remove();
            }

            Ordin cumparare = nou.getTip() == Ordin.Tip.BUY ? nou : existent;
            Ordin vanzare = nou.getTip() == Ordin.Tip.SELL ? nou : existent;
            Tranzactie tranzactie = new Tranzactie(istoric.size() + 1,
                    cumparare.getId(), vanzare.getId(), cumparare.getClient(), vanzare.getClient(),
                    nou.getCompanie(), nou.getPret(), cantitate);
            istoric.add(tranzactie);
            notificari.add(tranzactie);
        }
        if (nou.getCantitate() > 0) {
            ordine.add(nou);
        }
    }

    private static boolean valideaza(Ordin ordin) {
        if (ordin.getId() <= 0 || ordin.getClient() == null || ordin.getClient().isBlank()
                || ordin.getCompanie() == null || ordin.getCompanie().isBlank() || ordin.getTip() == null
                || ordin.getPret() <= 0 || ordin.getCantitate() <= 0) {
            System.err.printf("[ORDIN RESPINS] Ordin #%d: ID, pret si cantitate pozitive; client, companie si tip obligatorii.%n",
                    ordin.getId());
            return false;
        }
        return true;
    }
}
