package bursa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SimulareBursa {
    private static final String[] COMPANII = {"NVDA", "APPL"};
    private static final int NUMAR_TRADERI = 4;
    private static final int NUMAR_ACTIUNI = 20;
    private static final long PAUZA_MILLIS = 2_000;

    public static void main(String[] args) {
        BursaSincronizata bursa = new BursaSincronizata();
        AtomicInteger iduri = new AtomicInteger(1);
        AtomicBoolean finalizat = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService fire = Executors.newFixedThreadPool(NUMAR_TRADERI + 1);

        try {
            Future<Integer> observator = fire.submit(() -> observa(bursa, finalizat));
            initializeazaPiata(bursa, iduri);
            List<Future<?>> traderi = new ArrayList<>();
            for (int i = 0; i < NUMAR_TRADERI; i++) {
                String client = "Trader-" + (i + 1);
                long seed = 42L + i;
                traderi.add(fire.submit(() -> {
                    try {
                        start.await();
                        tranzactioneaza(bursa, iduri, client, new Random(seed));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.err.printf("[%s] Intrerupt; se opreste.%n", client);
                    }
                }));
            }
            System.out.println("Simulare: 4 traderi, 20 runde fiecare, pauza de 2 secunde intre runde.");
            start.countDown();
            for (int i = 0; i < traderi.size(); i++) {
                try {
                    traderi.get(i).get();
                } catch (ExecutionException e) {
                    System.err.printf("[Trader-%d] Firul s-a oprit: %s. Ceilalti traderi continua.%n",
                            i + 1, e.getCause());
                }
            }
            finalizat.set(true);
            try {
                int afisate = observator.get();
                System.out.printf("Istoric pastrat: %d | Tranzactii afisate de observator: %d%n",
                        bursa.citestePiata().istoric().size(), afisate);
            } catch (ExecutionException e) {
                System.err.printf("[OBSERVATOR] Firul s-a oprit: %s. Istoricul ramane disponibil.%n", e.getCause());
            }
            bursa.afiseazaStareFinala();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[SIMULARE] Intrerupta; se opresc firele active.");
        } catch (RuntimeException e) {
            System.err.printf("[SIMULARE] Nu poate continua: %s. Se opresc firele active.%n", e);
        } finally {
            finalizat.set(true);
            fire.shutdownNow();
            try {
                if (!fire.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("[SIMULARE] Unele fire nu s-au oprit in cele 5 secunde de asteptare.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[SIMULARE] Asteptarea opririi a fost intrerupta; semnalul de oprire a fost trimis.");
            }
        }
    }

    private static void initializeazaPiata(BursaSincronizata bursa, AtomicInteger iduri) {
        // Ordine initiale reale: traderii isi aleg preturile din carnetul de ordine.
        for (String companie : COMPANII) {
            int idCumparare = iduri.getAndIncrement();
            bursa.plaseazaOrdin(new Ordin(idCumparare, "Initial", companie, Ordin.Tip.BUY, 98, 30));
            bursa.plaseazaOrdin(new Ordin(iduri.getAndIncrement(), "Initial", companie, Ordin.Tip.SELL, 101, 30));
            bursa.modificaOrdin("Initial", idCumparare, 99, 30);
        }
    }

    private static void tranzactioneaza(BursaSincronizata bursa, AtomicInteger iduri,
                                        String client, Random random) throws InterruptedException {
        for (int runda = 0; runda < NUMAR_ACTIUNI; runda++) {
            if (runda > 0) {
                Thread.sleep(PAUZA_MILLIS);
            }
            try {
                String companie = COMPANII[random.nextInt(COMPANII.length)];
                Ordin.Tip tip = random.nextBoolean() ? Ordin.Tip.BUY : Ordin.Tip.SELL;
                boolean speculativ = random.nextInt(10) == 0;
                Ordin.Tip opus = tip == Ordin.Tip.BUY ? Ordin.Tip.SELL : Ordin.Tip.BUY;
                Ordin.Tip tipReferinta = speculativ ? tip : opus;
                Comparator<BursaSincronizata.OrdinVizibil> dupaPret =
                        Comparator.comparingInt(BursaSincronizata.OrdinVizibil::pret);
                if (tipReferinta == Ordin.Tip.BUY) {
                    dupaPret = dupaPret.reversed();
                }

                var referinta = bursa.citestePiata().ordine().stream()
                        .filter(o -> o.companie().equals(companie) && o.tip() == tipReferinta)
                        .filter(o -> speculativ || !o.client().equals(client))
                        .min(dupaPret);
                if (referinta.isEmpty()) {
                    continue;
                }
                int pret = referinta.get().pret();
                if (speculativ) {
                    int diferenta = 1 + random.nextInt(3);
                    pret += tip == Ordin.Tip.SELL ? diferenta : -diferenta;
                }
                bursa.plaseazaOrdin(new Ordin(iduri.getAndIncrement(), client, companie, tip,
                        pret, 1 + random.nextInt(10)));
            } catch (RuntimeException e) {
                System.err.printf("[%s] Runda %d a esuat: %s. Continua cu runda urmatoare.%n",
                        client, runda + 1, e);
            }
        }
    }

    private static int observa(BursaSincronizata bursa, AtomicBoolean finalizat) {
        int afisate = 0;
        // Dupa oprirea producatorilor, consumam si ultimele notificari.
        while (!finalizat.get() || bursa.areNotificari()) {
            try {
                Tranzactie tranzactie = bursa.asteaptaTranzactie(200);
                if (tranzactie != null) {
                    System.out.println(tranzactie);
                    afisate++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[OBSERVATOR] Intrerupt; se opreste. Istoricul ramane disponibil.");
                break;
            } catch (RuntimeException e) {
                System.err.printf("[OBSERVATOR] Afisarea a esuat: %s. Istoricul este pastrat; observatorul continua.%n", e);
            }
        }
        return afisate;
    }
}
