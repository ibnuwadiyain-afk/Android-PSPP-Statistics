* =========================================================================
* Bundled Sample 2: Contingency Tables (Crosstabs), Chi-Square & T-Tests
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

VALUE LABELS
  group 1 'Intensive Therapy' 2 'Standard Care' 3 'Placebo Control'
  /gender 1 'Male' 2 'Female'.

CROSSTABS
  /TABLES=group BY gender
  /FORMAT=AVALUE TABLES
  /STATISTICS=CHISQ PHI
  /CELLS=COUNT ROW COLUMN TOTAL.

T-TEST GROUPS=gender(1, 2)
  /VARIABLES=baseline_bp followup_bp outcome_score.

T-TEST PAIRS=baseline_bp WITH followup_bp (PAIRED).

ONEWAY outcome_score followup_bp BY group
  /STATISTICS=DESCRIPTIVES HOMOGENEITY.

EXECUTE.
