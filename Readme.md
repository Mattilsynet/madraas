# MADRaaS: Mattilsynets AdresseDataRegister as a Service

En tjeneste som legger adresser fra Matrikkelen på NATS, til glede for alle
Mattilsynets produktteam.

## Gjør deg klar for utvikling

```sh
make prepare-dev
```

Kjør så et REPL, gjerne med `cider-jack-in` i Emacs. Enjoy!

## Tester

Det kommer vel noen av dem etterhvert også:

```sh
bin/kaocha --watch
```

## Kjøre en fullstendig import

I utvikling:
```sh
clj -X:dev:synkroniser
```

I produksjon:
```sh
clj -X:prod:synkroniser
```
