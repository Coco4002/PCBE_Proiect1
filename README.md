# Bursa de actiuni

`BursaSincronizata` gestioneaza ordinele de cumparare si vanzare, potrivirea acestora
si istoricul tranzactiilor. Fiecare tranzactie este reprezentata printr-un obiect imuabil `Tranzactie`.
Clasele existente (`Ordin`, `InterfataBursa`, `BursaGresitaRaceCondition`) sunt pastrate.

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
