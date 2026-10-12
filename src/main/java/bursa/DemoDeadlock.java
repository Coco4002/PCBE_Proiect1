package bursa;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


public class DemoDeadlock {
    private static final String[] COMPANII = {"NVDA", "AAPL"};
    private static final int NUMAR_TRADERI = 4;
    private static final long LATENTA_IMPLICITA_MS = 50;
    private static final long TIMEOUT_IMPLICIT_S = 20;
    private static final long INTERVAL_VERIFICARE_MS = 200;
    private static final int PRET_IMPLICIT = 100;

    public static void main(String[] args) throws InterruptedException {
        long latentaMillis = args.length > 0 ? Long.parseLong(args[0]) : LATENTA_IMPLICITA_MS;
        long timeoutSec = args.length > 1 ? Long.parseLong(args[1]) : TIMEOUT_IMPLICIT_S;

        BursaGresitaDeadlock bursa = new BursaGresitaDeadlock(latentaMillis, latentaMillis > 0);
        AtomicInteger iduri = new AtomicInteger(1);
        AtomicBoolean oprire = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);
        initializeazaPiata(bursa, iduri);

        List<Thread> traderi = new ArrayList<>();
        for (int i = 0; i < NUMAR_TRADERI; i++) {
            String client = "Trader-" + (i + 1);
            long seed = 42L + i;
            Thread fir = new Thread(() -> {
                try {
                    start.await();
                    tranzactioneaza(bursa, iduri, client, new Random(seed), oprire);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, client);
            fir.setDaemon(true);
            traderi.add(fir);
            fir.start();
        }

        System.out.printf("[DEMO DEADLOCK] %d traderi, latenta %d ms, timeout %d s%n",
                NUMAR_TRADERI, latentaMillis, timeoutSec);
        ThreadMXBean jvm = ManagementFactory.getThreadMXBean();
        long inceput = System.nanoTime();
        long limita = inceput + TimeUnit.SECONDS.toNanos(timeoutSec);
        start.countDown();

        while (System.nanoTime() < limita) {
            Thread.sleep(INTERVAL_VERIFICARE_MS);
            if (deadlockRaportat(bursa, jvm, inceput)) {
                return;
            }
        }

        oprire.set(true);
        for (Thread fir : traderi) {
            fir.join(500);
        }


        if (deadlockRaportat(bursa, jvm, inceput)) {
            return;
        }
        System.out.printf("%nNiciun deadlock in %d s. Ruleaza din nou sau mareste latenta/timeout-ul.%n", timeoutSec);
        bursa.afiseazaStareFinala();
    }

    private static void initializeazaPiata(BursaGresitaDeadlock bursa, AtomicInteger iduri) {
        for (String companie : COMPANII) {
            bursa.plaseazaOrdin(new Ordin(iduri.getAndIncrement(), "Initial", companie, Ordin.Tip.BUY, 99, 30));
            bursa.plaseazaOrdin(new Ordin(iduri.getAndIncrement(), "Initial", companie, Ordin.Tip.SELL, 101, 30));
        }
    }


    private static void tranzactioneaza(BursaGresitaDeadlock bursa, AtomicInteger iduri, String client,
                                        Random random, AtomicBoolean oprire) {
        while (!oprire.get()) {
            try {
                BursaSincronizata.StarePiata piata = bursa.citestePiata();
                String companie = COMPANII[random.nextInt(COMPANII.length)];
                int actiune = random.nextInt(10);
                if (actiune < 2 && modificaUnOrdinPropriu(bursa, client, piata, random)) {
                    continue;
                }
                plaseazaOrdin(bursa, iduri, client, random, piata, companie, actiune == 2);
            } catch (RuntimeException e) {
                System.err.printf("[%s] Runda a esuat: %s. Continua.%n", client, e);
            }
        }
    }

   
   
    private static void plaseazaOrdin(BursaGresitaDeadlock bursa, AtomicInteger iduri, String client, Random random,
                                      BursaSincronizata.StarePiata piata, String companie, boolean speculativ) {
        Ordin.Tip tip = random.nextBoolean() ? Ordin.Tip.BUY : Ordin.Tip.SELL;
        Ordin.Tip opus = tip == Ordin.Tip.BUY ? Ordin.Tip.SELL : Ordin.Tip.BUY;
        Ordin.Tip tipReferinta = speculativ ? tip : opus;
        Comparator<BursaSincronizata.OrdinVizibil> dupaPret =
                Comparator.comparingInt(BursaSincronizata.OrdinVizibil::pret);
        if (tipReferinta == Ordin.Tip.BUY) {
            dupaPret = dupaPret.reversed();
        }

        int pret = piata.ordine().stream()
                .filter(o -> o.companie().equals(companie) && o.tip() == tipReferinta)
                .filter(o -> speculativ || !o.client().equals(client))
                .min(dupaPret)
                .map(BursaSincronizata.OrdinVizibil::pret)
                .orElseGet(() -> ultimulPret(piata, companie));
        if (speculativ) {
            int diferenta = 1 + random.nextInt(3);
            pret += tip == Ordin.Tip.SELL ? diferenta : -diferenta;
        }
        bursa.plaseazaOrdin(new Ordin(iduri.getAndIncrement(), client, companie, tip,
                pret, 1 + random.nextInt(10)));
    }



    private static boolean modificaUnOrdinPropriu(BursaGresitaDeadlock bursa, String client,
                                                  BursaSincronizata.StarePiata piata, Random random) {
        List<BursaSincronizata.OrdinVizibil> proprii = piata.ordine().stream()
                .filter(o -> o.client().equals(client))
                .toList();
        if (proprii.isEmpty()) {
            return false;
        }
        BursaSincronizata.OrdinVizibil ordin = proprii.get(random.nextInt(proprii.size()));
        int pretNou = ordin.tip() == Ordin.Tip.BUY ? ordin.pret() + 1 : ordin.pret() - 1;
        bursa.modificaOrdin(client, ordin.id(), pretNou, ordin.cantitate());
        return true;
    }



    private static int ultimulPret(BursaSincronizata.StarePiata piata, String companie) {
        List<Tranzactie> istoric = piata.istoric();
        for (int i = istoric.size() - 1; i >= 0; i--) {
            if (istoric.get(i).companie().equals(companie)) {
                return istoric.get(i).pret();
            }
        }
        return PRET_IMPLICIT;
    }

    private static boolean deadlockRaportat(BursaGresitaDeadlock bursa, ThreadMXBean jvm, long inceput) {
        long[] iduriBlocate = jvm.findDeadlockedThreads();
        if (iduriBlocate == null) {
            return false;
        }
        Set<String> blocate = Arrays.stream(jvm.getThreadInfo(iduriBlocate))
                .filter(Objects::nonNull)
                .map(ThreadInfo::getThreadName)
                .collect(Collectors.toCollection(TreeSet::new));

        System.out.printf("%n==================== DEADLOCK DETECTAT dupa %.1f s ====================%n",
                (System.nanoTime() - inceput) / 1e9);
        System.out.println("JVM confirma fire blocate: " + String.join(", ", blocate));

        Map<Thread, BursaGresitaDeadlock.LockNumit> asteapta = new HashMap<>();
        for (BursaGresitaDeadlock.LockNumit lock : bursa.lockuri()) {
            Thread proprietar = lock.proprietar();
            Collection<Thread> inAsteptare = lock.fireInAsteptare();
            if (proprietar == null && inAsteptare.isEmpty()) {
                continue;
            }
            String nume = inAsteptare.isEmpty() ? "-"
                    : inAsteptare.stream().map(Thread::getName).sorted().collect(Collectors.joining(", "));
            System.out.printf("  %-8s detinut de %-10s asteapta: %s%n",
                    lock.getNume(), proprietar == null ? "-" : proprietar.getName(), nume);
            for (Thread fir : inAsteptare) {
                asteapta.put(fir, lock);
            }
        }
        System.out.println(ciclu(asteapta, blocate));
        return true;
    }



    private static String ciclu(Map<Thread, BursaGresitaDeadlock.LockNumit> asteapta, Set<String> blocate) {
        Thread curent = asteapta.keySet().stream()
                .filter(fir -> blocate.contains(fir.getName()))
                .findFirst()
                .orElse(null);
        List<Thread> drum = new ArrayList<>();
        while (curent != null && !drum.contains(curent)) {
            drum.add(curent);
            BursaGresitaDeadlock.LockNumit lock = asteapta.get(curent);
            curent = lock == null ? null : lock.proprietar();
        }
        if (curent == null) {
            return "Ciclu: nu a putut fi reconstruit din lock-urile bursei.";
        }

        StringBuilder text = new StringBuilder("Ciclu: ").append(curent.getName());
        for (int i = drum.indexOf(curent); i < drum.size(); i++) {
            BursaGresitaDeadlock.LockNumit lock = asteapta.get(drum.get(i));
            text.append(" -(").append(lock.getNume()).append(")-> ").append(lock.proprietar().getName());
        }
        return text.toString();
    }
}
