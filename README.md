# Bursa de actiuni

`BursaSincronizata` gestioneaza ordinele de cumparare si vanzare, potrivirea acestora
si istoricul tranzactiilor. Fiecare tranzactie este reprezentata printr-un obiect imuabil `Tranzactie`.
Clasele `BursaGresitaRaceCondition` si `BursaGresitaDeadlock` sunt solutii gresite intentionat.

## Compilare

Este necesar un JDK 17 sau mai nou. Din directorul proiectului:

```powershell
mvn compile
```

Daca terminalul foloseste Java 8, selecteaza JDK-ul instalat pentru sesiunea curenta:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Folosirea componentei

- `plaseazaOrdin(Ordin)` copiaza ordinul si potriveste ordine opuse pentru aceeasi companie,
  la preturi egale, in ordinea sosirii. Un client nu tranzactioneaza cu el insusi.
  Se executa cantitatea minima, iar cantitatile ramase raman disponibile. Ordinele executate
  integral sunt eliminate din carnet, dar ID-urile lor nu pot fi reutilizate.
- `modificaOrdin(client, id, pret, cantitate)` schimba pretul si cantitatea **ramasa** a unui
  ordin propriu deschis. Ordinul pierde prioritatea anterioara si este potrivit din nou.
  Datele sunt verificate inainte de crearea ordinului inlocuitor. Daca sunt invalide,
  metoda afiseaza motivul in terminal si intoarce `false`, fara exceptie sau modificari
  in carnet, istoric ori notificari. Modificarea asteapta finalizarea tranzactiilor in curs.
  Un ordin inexistent sau apartinand altui client este de asemenea refuzat cu un mesaj
  si rezultatul `false`; o modificare acceptata intoarce `true`.
- `citestePiata()` returneaza o copie imuabila a ordinelor deschise si a istoricului,
  care reflecta aceeasi stare a pietei. Modificarea ulterioara a pietei nu schimba copia obtinuta.
- `asteaptaTranzactie(timeoutMillis)` permite unui singur observator sa primeasca tranzactiile
  finalizate si intoarce `null` la expirarea asteptarii. `areNotificari()` indica daca mai exista
  tranzactii de preluat. Preluarea unei notificari nu sterge tranzactia din istoric.
- `afiseazaStareFinala()` afiseaza ordinele ramase, numarul tranzactiilor si volumul total
  de actiuni tranzactionate.

Ordinele null, invalide sau cu ID reutilizat sunt respinse cu un mesaj in terminal,
fara exceptii si fara intrari in istoricul tranzactiilor. Un ordin invalid nu rezerva ID-ul.

Istoricul este pastrat in memorie si include ID-urile ordinelor, cumparatorul, vanzatorul,
compania, pretul si cantitatea fiecarei tranzactii. Componenta nu gestioneaza solduri sau
portofolii si nu necesita dependente externe.

## Solutia gresita 2: deadlock (`BursaGresitaDeadlock`)

Fiecare companie are propriul lock, ca o tranzactie pe NVDA sa nu blocheze ordinele pe AAPL
("nicio tranzactie in curs pe actiunile respective"), iar istoricul comun are lock-ul `ISTORIC`.
Regulile de potrivire sunt identice cu `BursaSincronizata` si toate datele comune sunt protejate,
deci nu exista race condition. Defectul este ordinea lock-urilor:

| Metoda | Ordinea lock-urilor |
|---|---|
| `plaseazaOrdin`, `modificaOrdin` | COMPANIE -> ISTORIC (la fiecare tranzactie) |
| `citestePiata` | ISTORIC -> toate COMPANIILE, alfabetic |

Un trader care tranzactioneaza tine `NVDA` si cere `ISTORIC`, in timp ce un trader care citeste
piata tine `ISTORIC` si cere `NVDA`: asteptare circulara. Sunt indeplinite toate conditiile Coffman:
excludere mutuala (`ReentrantLock`), hold and wait, fara preemptiune (`lock()` fara timeout) si
asteptare circulara.

Rulare demonstratie (dupa `mvn compile`):

```
java -cp target/classes bursa.DemoDeadlock [latentaMs] [timeoutSec]
```

Implicit latenta este 50 ms si timeout-ul 20 s. Latenta este o pauza intre primul si al doilea lock:
nu cauzeaza deadlock-ul, doar il face reproductibil. Cu `0` deadlock-ul ramane posibil, dar rar.
Firul principal foloseste `ThreadMXBean.findDeadlockedThreads()` si, la detectare, afiseaza cine
detine si cine asteapta fiecare lock, precum si ciclul format.

Remedieri posibile:
- ordine globala a lock-urilor: mereu COMPANIILE alfabetic, apoi ISTORIC (elimina asteptarea circulara);
- adaugarea tranzactiilor in istoric dupa eliberarea lock-ului companiei (elimina hold and wait);
- `tryLock(timeout)` cu renuntare si reincercare (introduce preemptiune).
