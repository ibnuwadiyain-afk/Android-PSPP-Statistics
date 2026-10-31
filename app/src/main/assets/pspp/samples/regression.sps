* =========================================================================
* Bundled Sample 3: Correlation, OLS Linear Regression & Scale Reliability
* Dataset: clinical_trial.csv
* =========================================================================

GET DATA /TYPE=TXT
  /FILE='clinical_trial.csv'
  /DELIMITERS=","
  /FIRSTCASE=2
  /VARIABLES=
    id F4.0 group F2.0 gender F2.0 age F4.0
    baseline_bp F6.1 followup_bp F6.1 cholesterol F6.1 bmi F5.1
    adherence_score F5.1 qol_item1 F2.0 qol_item2 F2.0 qol_item3 F2.0 qol_item4 F2.0
    outcome_score F6.1.

CORRELATIONS
  /VARIABLES=age baseline_bp followup_bp adherence_score outcome_score
  /PRINT=TWOTAIL NOSIG
  /STATISTICS=DESCRIPTIVES.

REGRESSION
  /VARIABLES=adherence_score baseline_bp age outcome_score
  /DEPENDENT=outcome_score
  /METHOD=ENTER adherence_score baseline_bp age
  /STATISTICS=COEFF R ANOVA CI.

RELIABILITY
  /VARIABLES=qol_item1 qol_item2 qol_item3 qol_item4
  /SCALE('Quality of Life 4-Item Scale') ALL
  /MODEL=ALPHA
  /SUMMARY=TOTAL.

EXECUTE.
