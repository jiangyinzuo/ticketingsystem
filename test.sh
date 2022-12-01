#!/bin/sh

javac -encoding UTF-8 -cp . ticketingsystem/Test.java
java -XX:-RestrictContended -cp . ticketingsystem/Test