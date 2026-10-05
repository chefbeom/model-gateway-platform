-- Keep this additive change after the deployed V36 baseline.
ALTER TABLE model_deployment
    ADD COLUMN feature_support_json TEXT NULL;
