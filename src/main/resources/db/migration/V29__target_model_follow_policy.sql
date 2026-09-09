ALTER TABLE service_target
    ADD COLUMN follow_model_changes BOOLEAN NOT NULL DEFAULT TRUE AFTER enabled;

UPDATE service_target AS st
JOIN model_deployment AS md ON md.id = st.deployment_id
SET st.follow_model_changes = FALSE
WHERE md.external_provider_id IS NOT NULL;
