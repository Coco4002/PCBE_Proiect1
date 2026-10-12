package bursa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

// Solutie gresita 2: DEADLOCK prin ordine inversa de blocare.
// Fiecare companie are lock-ul ei ("nicio tranzactie in curs pe actiunile respective"),
// iar istoricul comun are un lock separat. Toate datele comune sunt protejate, deci nu exista
// race condition; singurul defect este ordinea in care se iau lock-urile:
//   plaseazaOrdin / modificaOrdin: COMPANIE -> ISTORIC
//   citestePiata:                  ISTORIC  -> COMPANII (alfabetic)
public class BursaGresitaDeadlock implements InterfataBursa {

    // ReentrantLock cu nume; expune proprietarul si firele care asteapta, pentru raportul de deadlock.
    public static final class LockNumit extends ReentrantLock {
        private final String nume;

        LockNumit(String nume) {
            this.nume = nume;
        }

        public String getNume() { return nume; }
        public Thread proprietar() { return getOwner(); }
        public Collection<Thread> fireInAsteptare() { return getQueuedThreads(); }
    }

    private static final class Carnet {
        final LockNumit lock;
        final List<Ordin> ordine = new ArrayList<>();

        Carnet(String companie) {
            lock = new LockNumit(companie);
        }
    }

    private final Map<String, Carnet> carnete = new ConcurrentHashMap<>();
    private final List<Tranzactie> istoric = new ArrayList<>();
    private final LockNumit lockIstoric = new LockNumit("ISTORIC");
    private final Set<Integer> iduriFolosite = ConcurrentHashMap.newKeySet();
    // Pauza intre primul si al doilea lock: nu cauzeaza deadlock-ul, doar il face reproductibil.
    private final long latentaMillis;
    private final boolean jurnal;

    public BursaGresitaDeadlock() {
        this(0, false);
    }

    public BursaGresitaDeadlock(long latentaMillis, boolean jurnal) {
        this.latentaMillis = latentaMillis;
        this.jurnal = jurnal;
    }

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
        if (!iduriFolosite.add(copie.getId())) {
            System.err.printf("[ORDIN RESPINS] ID de ordin deja folosit: %d.%n", copie.getId());
            return;
        }

        // Lock doar pe compania ordinului, ca tranzactiile pe alte companii sa continue in paralel.
        Carnet carnet = carnete.computeIfAbsent(copie.getCompanie(), Carnet::new);
        carnet.lock.lock();                                   // LOCK 1: COMPANIE
        try {
            potriveste(carnet, copie);                        // LOCK 2: ISTORIC, la fiecare tranzactie
        } finally {
            carnet.lock.unlock();
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
        // Cautam companie cu companie, tinand un singur lock de companie la un moment dat.
        for (Carnet carnet : carnete.values()) {
            carnet.lock.lock();                               // LOCK 1: COMPANIE
            try {
                Iterator<Ordin> iterator = carnet.ordine.iterator();
                while (iterator.hasNext()) {
                    Ordin vechi = iterator.next();
                    if (vechi.getId() == id && vechi.getClient().equals(client)) {
                        iterator.remove();
                        potriveste(carnet, new Ordin(id, client, vechi.getCompanie(), vechi.getTip(), pret, cantitate));
                        return true;                          // LOCK 2: ISTORIC, in potriveste
                    }
                }
            } finally {
                carnet.lock.unlock();
            }
        }
        System.err.printf("[MODIFICARE RESPINSA] Ordin #%d: nu exista un ordin deschis cu acest ID pentru clientul %s.%n", id, client);
        return false;
    }

    public BursaSincronizata.StarePiata citestePiata() {
        // Blocam intai istoricul, ca sa nu apara tranzactii noi cat timp copiem carnetele.
        lockIstoric.lock();                                   // LOCK 1: ISTORIC
        try {
            List<Tranzactie> copieIstoric = List.copyOf(istoric);
            // Luam carnetele in ordine alfabetica, ca sa evitam deadlock-ul intre companii.
            List<Carnet> sortate = carnete.keySet().stream().sorted().map(carnete::get).toList();
            jurnal(lockIstoric.getNume(), sortate.stream().map(c -> c.lock.getNume()).toList());
            pauza();

            List<LockNumit> luate = new ArrayList<>();
            try {
                for (Carnet carnet : sortate) {
                    carnet.lock.lock();                       // LOCK 2: COMPANII -> ordine inversa fata de plaseazaOrdin
                    luate.add(carnet.lock);
                }
                List<BursaSincronizata.OrdinVizibil> copii = sortate.stream()
                        .flatMap(carnet -> carnet.ordine.stream())
                        .map(o -> new BursaSincronizata.OrdinVizibil(o.getId(), o.getClient(), o.getCompanie(),
                                o.getTip(), o.getPret(), o.getCantitate()))
                        .toList();
                return new BursaSincronizata.StarePiata(copii, copieIstoric);
            } finally {
                for (int i = luate.size() - 1; i >= 0; i--) {
                    luate.get(i).unlock();
                }
            }
        } finally {
            lockIstoric.unlock();
        }
    }

    // Toate lock-urile bursei (ISTORIC, apoi companiile alfabetic), pentru raportul de deadlock.
    public List<LockNumit> lockuri() {
        List<LockNumit> lockuri = new ArrayList<>();
        lockuri.add(lockIstoric);
        carnete.keySet().stream().sorted().forEach(companie -> lockuri.add(carnete.get(companie).lock));
        return lockuri;
    }

    @Override
    public void afiseazaStareFinala() {
        BursaSincronizata.StarePiata stare = citestePiata();
        System.out.println("\nOrdine ramase (Deadlock):");
        for (BursaSincronizata.OrdinVizibil ordin : stare.ordine()) {
            System.out.printf("%s #%d [%s] %d buc @ %d lei (Client: %s)%n",
                    ordin.tip(), ordin.id(), ordin.companie(), ordin.cantitate(), ordin.pret(), ordin.client());
        }
        int volum = stare.istoric().stream().mapToInt(Tranzactie::cantitate).sum();
        System.out.printf("Total: %d tranzactii, %d actiuni tranzactionate.%n", stare.istoric().size(), volum);
    }

    // Se apeleaza doar sub lock-ul companiei; aceleasi reguli de potrivire ca BursaSincronizata.
    private void potriveste(Carnet carnet, Ordin nou) {
        Iterator<Ordin> iterator = carnet.ordine.iterator();
        while (nou.getCantitate() > 0 && iterator.hasNext()) {
            Ordin existent = iterator.next();
            if (existent.getTip() == nou.getTip()
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
            jurnal(carnet.lock.getNume(), List.of(lockIstoric.getNume()));
            pauza();                                          // "calculul decontarii"
            lockIstoric.lock();                               // LOCK 2: ISTORIC, tinand inca lock-ul companiei
            try {
                Tranzactie tranzactie = new Tranzactie(istoric.size() + 1,
                        cumparare.getId(), vanzare.getId(), cumparare.getClient(), vanzare.getClient(),
                        nou.getCompanie(), nou.getPret(), cantitate);
                istoric.add(tranzactie);
                if (jurnal) {
                    System.out.println(tranzactie);
                }
            } finally {
                lockIstoric.unlock();
            }
        }
        if (nou.getCantitate() > 0) {
            carnet.ordine.add(nou);
        }
    }

    private void jurnal(String tinut, List<String> cerute) {
        if (jurnal) {
            System.out.printf("[%s] tine %s -> cere %s%n",
                    Thread.currentThread().getName(), tinut, String.join(", ", cerute));
        }
    }

    private void pauza() {
        if (latentaMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(latentaMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
