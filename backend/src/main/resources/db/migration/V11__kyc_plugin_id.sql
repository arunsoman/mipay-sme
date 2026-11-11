-- V11: kyc_workflow_plugins needs the entity's surrogate id (KycIdEntity). V8
-- created the table keyed on (interface_key, plugin_key) only; the plugin
-- registry publishes rows through JPA, which expects the id column.
alter table kyc_workflow_plugins add column if not exists id varchar(36);
update kyc_workflow_plugins set id = gen_random_uuid() where id is null;
alter table kyc_workflow_plugins alter column id set not null;
alter table kyc_workflow_plugins drop constraint if exists kyc_workflow_plugins_pkey;
alter table kyc_workflow_plugins add primary key (id);

-- Organization carries its country (D2): profile resolution keys on country first.
alter table organizations add column if not exists country varchar(2);
update organizations set country = 'NA' where country is null;
