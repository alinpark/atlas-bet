# Modèles

L'engine propose une loi de Poisson pour le football, Elo général ou surface pour
le tennis et Elo avec avantage domicile pour le basketball. Tous sont UNVALIDATED.
Les probabilités utilisent des features strictement antérieures au début.
Brier, log loss et ECE sont calculés sur les échantillons fournis. L'évaluation
historique existante utilise son propre modèle de référence Elo. Les bornes
d'incertitude du moteur sont nulles faute de validation et de calibration réelles.
