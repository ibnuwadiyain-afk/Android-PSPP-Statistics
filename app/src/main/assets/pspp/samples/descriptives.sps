* =========================================================================
* Bundled Sample 1: Descriptive Statistics & Frequency Distributions
* Dataset: clinical_trial.csv (N = 20 patients across 3 treatment arms)
* =========================================================================

GET DATA /TYPE=TXT
  /FILE='clinical_trial.csv'
  /DELIMITERS=","
  /FIRSTCASE=2
  /VARIABLES=
    id F4.0
    group F2.0
    gender F2.0
    age F4.0
    baseline_bp F6.1
    followup_bp F6.1
    cholesterol F6.1
    bmi F5.1
    adherence_score F5.1
    qol_item1 F2.0
    qol_item2 F2.0
    qol_item3 F2.0
    qol_item4 F2.0
    outcome_score F6.1.

VARIABLE LABELS
  id 'Patient ID'
  group 'Treatment Arm'
  gender 'Patient Gender'
  age 'Age in Years'
  baseline_bp 'Baseline Systolic BP (mmHg)'
  followup_bp 'Follow-up Systolic BP (mmHg)'
  cholesterol 'Total Cholesterol (mg/dL)'
  bmi 'Body Mass Index (kg/m2)'
  adherence_score 'Medication Adherence (%)'
  outcome_score 'Composite Clinical Outcome Score'.

VALUE LABELS
  group 1 'Intensive Therapy' 2 'Standard Care' 3 'Placebo Control'
  /gender 1 'Male' 2 'Female'.

DESCRIPTIVES VARIABLES=age baseline_bp followup_bp cholesterol bmi outcome_score
  /STATISTICS=MEAN STDDEV MIN MAX VARIANCE SKEWNESS KURTOSIS SEMEAN.

FREQUENCIES VARIABLES=group gender
  /FORMAT=AVALUE TABLE
  /STATISTICS=MEAN STDDEV MEDIAN MODE MIN MAX.

EXECUTE.
