--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: account_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_mappings (
    created_at timestamp(6) with time zone,
    balance_account_id character varying(36) NOT NULL,
    connector_id character varying(36),
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    issuer_account_ref character varying(255) NOT NULL,
    legal_holder character varying(255) NOT NULL
);


--
-- Name: activation_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.activation_tokens (
    id character varying(36) NOT NULL,
    token_hash character varying(64) NOT NULL,
    user_id character varying(36) NOT NULL,
    issued_by character varying(36),
    expires_at timestamp(6) with time zone NOT NULL,
    used_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone
);


--
-- Name: advance_repayments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.advance_repayments (
    amount numeric(18,2) NOT NULL,
    collection_date date,
    eligible_sales_base numeric(18,2),
    created_at timestamp(6) with time zone,
    advance_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    source_payment_id character varying(36)
);


--
-- Name: advances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.advances (
    principal_amount numeric(18,2) NOT NULL,
    repayment_rate numeric(5,4) NOT NULL,
    requested_amount numeric(18,2) NOT NULL,
    total_repayment numeric(18,2) NOT NULL,
    closed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    funded_at timestamp(6) with time zone,
    assessment_id character varying(36),
    id character varying(36) NOT NULL,
    lender_id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    purpose character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT advances_status_check CHECK (((status)::text = ANY ((ARRAY['OFFERED'::character varying, 'ACCEPTED'::character varying, 'FUNDED'::character varying, 'REPAYING'::character varying, 'CLOSED'::character varying, 'DEFAULTED'::character varying])::text[])))
);


--
-- Name: aml_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.aml_alerts (
    closed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    entity_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    reference_id character varying(36),
    alert_type character varying(255) NOT NULL,
    details character varying(1000),
    entity_type character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT aml_alerts_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'INVESTIGATING'::character varying, 'CLOSED'::character varying])::text[])))
);


--
-- Name: api_clients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_clients (
    rate_limit_tps integer,
    created_at timestamp(6) with time zone,
    last_rotated_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    client_id character varying(255) NOT NULL,
    client_name character varying(255) NOT NULL,
    scopes character varying(1000),
    status character varying(255) NOT NULL,
    CONSTRAINT api_clients_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: arrears_cases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.arrears_cases (
    days_past_due integer,
    opened_at timestamp(6) with time zone,
    resolved_at timestamp(6) with time zone,
    advance_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    lender_id character varying(36) NOT NULL,
    status character varying(255),
    CONSTRAINT arrears_cases_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'RESOLVED'::character varying])::text[])))
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    simulated boolean DEFAULT false,
    created_at timestamp(6) with time zone,
    actor_id character varying(36),
    entity_id character varying(36),
    id character varying(36) NOT NULL,
    action character varying(255) NOT NULL,
    actor_role character varying(255),
    after_hash character varying(255),
    before_hash character varying(255),
    entity_type character varying(255),
    ip_address character varying(255),
    meta character varying(1000),
    actor_kind character varying(24),
    link_id character varying(36),
    acr character varying(16)
);


--
-- Name: auth_events_seen; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_events_seen (
    event_id character varying(255) NOT NULL,
    provider character varying(64) NOT NULL,
    subject character varying(255) NOT NULL,
    event_type character varying(64) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: auth_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_outbox (
    id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    provider character varying(64) NOT NULL,
    command character varying(64) NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_until timestamp with time zone,
    last_error character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    dedupe_key character varying(255),
    CONSTRAINT auth_outbox_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'DONE'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: backups; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backups (
    completed_at timestamp(6) with time zone,
    started_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    backup_type character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT backups_backup_type_check CHECK (((backup_type)::text = ANY ((ARRAY['FULL'::character varying, 'INCREMENTAL'::character varying])::text[]))),
    CONSTRAINT backups_status_check CHECK (((status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: balance_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.balance_accounts (
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    owner_id character varying(36) NOT NULL,
    currency character varying(255) NOT NULL,
    issuer_account_ref character varying(255),
    legal_holder character varying(255),
    owner_type character varying(255) NOT NULL,
    status character varying(255),
    CONSTRAINT balance_accounts_owner_type_check CHECK (((owner_type)::text = ANY ((ARRAY['MERCHANT'::character varying, 'SUPPLIER'::character varying])::text[]))),
    CONSTRAINT balance_accounts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'FROZEN'::character varying, 'CLOSED'::character varying])::text[])))
);


--
-- Name: balance_holds; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.balance_holds (
    amount numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    reason character varying(255) NOT NULL,
    reference character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT balance_holds_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CONVERTED'::character varying, 'RELEASED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: card_terminals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.card_terminals (
    created_at timestamp(6) with time zone,
    last_seen_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    status character varying(255) NOT NULL,
    terminal_id character varying(255) NOT NULL,
    CONSTRAINT card_terminals_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);


--
-- Name: complaints; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.complaints (
    created_at timestamp(6) with time zone,
    resolved_at timestamp(6) with time zone,
    assigned_to character varying(36),
    id character varying(36) NOT NULL,
    merchant_id character varying(36),
    supplier_id character varying(36),
    category character varying(255),
    description text,
    resolution character varying(1000),
    status character varying(255) NOT NULL,
    CONSTRAINT complaints_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'IN_PROGRESS'::character varying, 'RESOLVED'::character varying, 'CLOSED'::character varying])::text[])))
);


--
-- Name: consent_withdrawals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consent_withdrawals (
    created_at timestamp(6) with time zone,
    withdrawn_at timestamp(6) with time zone,
    consent_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    reason character varying(255)
);


--
-- Name: credit_assessments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.credit_assessments (
    avg_monthly_net_receipts numeric(18,2),
    eligible_total numeric(18,2),
    eligible_trading_days integer,
    score numeric(5,0),
    window_end date,
    window_start date,
    created_at timestamp(6) with time zone,
    consent_id character varying(36),
    id character varying(36) NOT NULL,
    lender_id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    decision character varying(255) NOT NULL,
    reason_codes character varying(500),
    CONSTRAINT credit_assessments_decision_check CHECK (((decision)::text = ANY ((ARRAY['APPROVED'::character varying, 'DECLINED'::character varying, 'MANUAL_REVIEW'::character varying])::text[])))
);


--
-- Name: credit_consents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.credit_consents (
    granted_at timestamp(6) with time zone,
    withdrawn_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    lender_id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    data_fields character varying(1000),
    purpose character varying(255) NOT NULL,
    retention_period character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT credit_consents_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'WITHDRAWN'::character varying])::text[])))
);


--
-- Name: deployments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.deployments (
    deployed_at timestamp(6) with time zone,
    deployed_by character varying(36),
    id character varying(36) NOT NULL,
    environment character varying(255) NOT NULL,
    notes character varying(500),
    status character varying(255) NOT NULL,
    version character varying(255) NOT NULL,
    CONSTRAINT deployments_status_check CHECK (((status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILED'::character varying, 'ROLLED_BACK'::character varying])::text[])))
);


--
-- Name: devices_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.devices_sessions (
    created_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    device_fingerprint character varying(255),
    mfa_method character varying(255),
    token_hash character varying(255)
);


--
-- Name: disputes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.disputes (
    created_at timestamp(6) with time zone,
    resolved_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    payment_id character varying(36) NOT NULL,
    details character varying(1000),
    reason character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT disputes_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'WON'::character varying, 'LOST'::character varying])::text[])))
);


--
-- Name: dsar_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dsar_requests (
    completed_at timestamp(6) with time zone,
    requested_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    requested_by character varying(36),
    payload_ref character varying(500),
    status character varying(255) NOT NULL,
    CONSTRAINT dsar_requests_status_check CHECK (((status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying])::text[])))
);


--
-- Name: eligibility_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.eligibility_rules (
    effective_from date,
    enabled boolean DEFAULT true,
    approved_by character varying(36),
    id character varying(36) NOT NULL,
    definition character varying(500),
    rule_code character varying(255) NOT NULL
);


--
-- Name: fee_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fee_reports (
    amount_billed numeric(18,2),
    amount_collected numeric(18,2),
    report_date date NOT NULL,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    fee_code character varying(255) NOT NULL
);


--
-- Name: fee_tariffs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fee_tariffs (
    effective_from date NOT NULL,
    effective_to date,
    external_cost numeric(8,4),
    tariff_value numeric(8,4) NOT NULL,
    approved_by character varying(36),
    id character varying(36) NOT NULL,
    code character varying(255) NOT NULL,
    cost_basis character varying(255) NOT NULL,
    note character varying(300),
    rate_type character varying(255) NOT NULL,
    CONSTRAINT fee_tariffs_cost_basis_check CHECK (((cost_basis)::text = ANY ((ARRAY['CONTRACTED'::character varying, 'PROVISION'::character varying, 'UNQUOTED'::character varying])::text[]))),
    CONSTRAINT fee_tariffs_rate_type_check CHECK (((rate_type)::text = ANY ((ARRAY['PERCENTAGE'::character varying, 'FIXED'::character varying])::text[])))
);


--
-- Name: fee_transactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fee_transactions (
    amount numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    fee_code character varying(255) NOT NULL,
    reference character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT fee_transactions_status_check CHECK (((status)::text = ANY ((ARRAY['APPLIED'::character varying, 'REVERSED'::character varying])::text[])))
);


--
-- Name: identity_links; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.identity_links (
    id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    provider character varying(64) NOT NULL,
    issuer character varying(255) NOT NULL,
    subject character varying(255) NOT NULL,
    status character varying(16) NOT NULL,
    idp_enabled boolean DEFAULT false NOT NULL,
    max_aal character varying(16) DEFAULT 'AAL1'::character varying NOT NULL,
    created_by character varying(36),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    activated_at timestamp with time zone,
    last_login_at timestamp with time zone,
    CONSTRAINT identity_links_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'DISABLED'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: incidents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.incidents (
    acknowledged_late boolean,
    acknowledged_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    resolved_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    description text,
    severity character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    title character varying(255) NOT NULL,
    CONSTRAINT incidents_severity_check CHECK (((severity)::text = ANY ((ARRAY['CRITICAL'::character varying, 'HIGH'::character varying, 'MEDIUM'::character varying, 'LOW'::character varying])::text[]))),
    CONSTRAINT incidents_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'MITIGATED'::character varying, 'RESOLVED'::character varying])::text[])))
);


--
-- Name: integration_connectors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.integration_connectors (
    health_latency_ms integer,
    created_at timestamp(6) with time zone,
    last_health_check timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    auth_type character varying(255),
    base_url character varying(255),
    capabilities character varying(2000),
    connector_type character varying(255) NOT NULL,
    credentials_ref character varying(255),
    environment character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT integration_connectors_auth_type_check CHECK (((auth_type)::text = ANY ((ARRAY['OAUTH2'::character varying, 'API_KEY'::character varying, 'MTLS'::character varying])::text[]))),
    CONSTRAINT integration_connectors_connector_type_check CHECK (((connector_type)::text = ANY ((ARRAY['BANK_ISSUER'::character varying, 'LENDER'::character varying, 'IPS'::character varying, 'CARD'::character varying, 'QR'::character varying, 'KYC'::character varying, 'NOTIFICATION'::character varying])::text[]))),
    CONSTRAINT integration_connectors_environment_check CHECK (((environment)::text = ANY ((ARRAY['SANDBOX'::character varying, 'UAT'::character varying, 'PRODUCTION'::character varying])::text[]))),
    CONSTRAINT integration_connectors_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: inventory_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_items (
    current_stock numeric(18,2),
    reorder_level numeric(18,2),
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    name character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    unit character varying(255),
    CONSTRAINT inventory_items_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying])::text[])))
);


--
-- Name: invoice_payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invoice_payments (
    amount numeric(18,2) NOT NULL,
    confirmed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    hold_id character varying(36),
    id character varying(36) NOT NULL,
    invoice_id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    supplier_id character varying(36) NOT NULL,
    bank_reference character varying(255),
    idempotency_key character varying(255) NOT NULL,
    payment_route character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT invoice_payments_payment_route_check CHECK (((payment_route)::text = ANY ((ARRAY['BOOK_TRANSFER'::character varying, 'EFT'::character varying])::text[]))),
    CONSTRAINT invoice_payments_status_check CHECK (((status)::text = ANY ((ARRAY['HELD'::character varying, 'PROCESSING'::character varying, 'CONFIRMED'::character varying, 'FAILED'::character varying, 'TIMED_OUT'::character varying])::text[])))
);


--
-- Name: invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invoices (
    amount numeric(18,2) NOT NULL,
    issued_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    order_id character varying(36),
    supplier_id character varying(36) NOT NULL,
    currency character varying(255) NOT NULL,
    invoice_number character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    unique_reference character varying(255) NOT NULL,
    CONSTRAINT invoices_status_check CHECK (((status)::text = ANY ((ARRAY['ISSUED'::character varying, 'PARTIALLY_PAID'::character varying, 'PAID'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: issuer_balance_ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.issuer_balance_ledger (
    amount numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    balance_account_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    owner_id character varying(36) NOT NULL,
    currency character varying(255) NOT NULL,
    entry_type character varying(255) NOT NULL,
    idempotency_key character varying(255) NOT NULL,
    issuer_reference character varying(255),
    owner_type character varying(255) NOT NULL,
    CONSTRAINT issuer_balance_ledger_entry_type_check CHECK (((entry_type)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying])::text[]))),
    CONSTRAINT issuer_balance_ledger_owner_type_check CHECK (((owner_type)::text = ANY ((ARRAY['MERCHANT'::character varying, 'SUPPLIER'::character varying])::text[])))
);


--
-- Name: khata_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.khata_accounts (
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    customer_name character varying(255) NOT NULL,
    customer_phone character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT khata_accounts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SETTLED'::character varying, 'WRITTEN_OFF'::character varying])::text[])))
);


--
-- Name: khata_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.khata_entries (
    amount numeric(18,2) NOT NULL,
    due_date date,
    created_at timestamp(6) with time zone,
    settled_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    khata_id character varying(36) NOT NULL,
    entry_type character varying(255) NOT NULL,
    CONSTRAINT khata_entries_entry_type_check CHECK (((entry_type)::text = ANY ((ARRAY['CHARGE'::character varying, 'PAYMENT'::character varying])::text[])))
);


--
-- Name: kyc_application_submissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_application_submissions (
    id character varying(36) NOT NULL,
    application_id character varying(36) NOT NULL,
    submission_no integer NOT NULL,
    doc jsonb NOT NULL,
    submitted_by character varying(36),
    submitted_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: kyc_applications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_applications (
    id character varying(36) NOT NULL,
    org_id character varying(36) NOT NULL,
    org_type character varying(16) NOT NULL,
    org_name character varying(255),
    parent_org_id character varying(36),
    tree_path character varying(1024) NOT NULL,
    depth integer NOT NULL,
    is_suspended boolean DEFAULT false NOT NULL,
    status character varying(16) NOT NULL,
    application_json jsonb NOT NULL,
    submitted_json jsonb,
    created_by character varying(36),
    updated_by character varying(36),
    submitted_by character varying(36),
    submitted_at timestamp(6) with time zone,
    decided_by character varying(36),
    decided_at timestamp(6) with time zone,
    decision_note character varying(1000),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    country character varying(2),
    licence_type character varying(16),
    subject_type character varying(16),
    entity_type character varying(32),
    profile_id character varying(36),
    schema_version_id character varying(36),
    workflow_key character varying(64),
    workflow_version integer,
    workflow_plan jsonb,
    current_stage character varying(64),
    submission_no integer DEFAULT 1 NOT NULL,
    risk_rating character varying(16),
    next_review_date timestamp(6) with time zone,
    retain_until timestamp(6) with time zone,
    needs_upgrade boolean DEFAULT false NOT NULL,
    CONSTRAINT kyc_applications_independence CHECK (((decided_by IS NULL) OR (submitted_by IS NULL) OR ((decided_by)::text <> (submitted_by)::text))),
    CONSTRAINT kyc_applications_licence_type_check CHECK (((licence_type)::text = ANY ((ARRAY['PSP'::character varying, 'EMI'::character varying])::text[]))),
    CONSTRAINT kyc_applications_org_type_check CHECK (((org_type)::text = ANY ((ARRAY['PLATFORM'::character varying, 'MANUFACTURER'::character varying, 'DISTRIBUTOR'::character varying, 'SUPPLIER'::character varying, 'MERCHANT'::character varying, 'LENDER'::character varying])::text[]))),
    CONSTRAINT kyc_applications_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ENTRY_CHECK'::character varying, 'VERIFYING'::character varying, 'IN_REVIEW'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'BLOCKED'::character varying, 'WITHDRAWN'::character varying])::text[]))),
    CONSTRAINT kyc_applications_subject_type_check CHECK (((subject_type)::text = ANY ((ARRAY['BUSINESS'::character varying, 'INDIVIDUAL'::character varying])::text[])))
);


--
-- Name: kyc_checks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_checks (
    checked_at timestamp(6) with time zone,
    entity_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    check_type character varying(255) NOT NULL,
    details character varying(500),
    entity_type character varying(255) NOT NULL,
    result character varying(255) NOT NULL,
    CONSTRAINT kyc_checks_check_type_check CHECK (((check_type)::text = ANY ((ARRAY['IDENTITY'::character varying, 'SANCTIONS'::character varying, 'ADVERSE_MEDIA'::character varying])::text[]))),
    CONSTRAINT kyc_checks_result_check CHECK (((result)::text = ANY ((ARRAY['PASS'::character varying, 'FAIL'::character varying, 'MANUAL_REVIEW'::character varying])::text[])))
);


--
-- Name: kyc_fields; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_fields (
    id character varying(36) NOT NULL,
    section_id character varying(36) NOT NULL,
    key character varying(64) NOT NULL,
    label character varying(200) NOT NULL,
    help_text character varying(500),
    placeholder character varying(200),
    sort integer DEFAULT 0 NOT NULL,
    data_type character varying(16) NOT NULL,
    list_code character varying(64),
    list_parent_field character varying(64),
    required character varying(16) NOT NULL,
    required_when jsonb,
    visible_when jsonb,
    pattern character varying(200),
    "normalize" character varying(16),
    min_len integer,
    max_len integer,
    min_value numeric(38,10),
    max_value numeric(38,10),
    date_rule character varying(16),
    derived_from character varying(200),
    canonical_tag character varying(64),
    sensitivity character varying(16) DEFAULT 'NONE'::character varying NOT NULL,
    mask character varying(16),
    CONSTRAINT kyc_fields_data_type_check CHECK (((data_type)::text = ANY ((ARRAY['TEXT'::character varying, 'NUMBER'::character varying, 'MONEY'::character varying, 'PERCENT'::character varying, 'DATE'::character varying, 'BOOLEAN'::character varying, 'ENUM'::character varying, 'MULTI_ENUM'::character varying, 'COUNTRY'::character varying, 'EMAIL'::character varying, 'PHONE'::character varying, 'URL'::character varying])::text[]))),
    CONSTRAINT kyc_fields_date_rule_check CHECK (((date_rule)::text = ANY ((ARRAY['PAST'::character varying, 'FUTURE'::character varying, 'ANY'::character varying])::text[]))),
    CONSTRAINT kyc_fields_mask_check CHECK (((mask)::text = ANY ((ARRAY['LAST4'::character varying, 'HIDE'::character varying, 'YEAR_ONLY'::character varying])::text[]))),
    CONSTRAINT kyc_fields_normalize_check CHECK ((("normalize")::text = 'PUNCT'::text)),
    CONSTRAINT kyc_fields_required_check CHECK (((required)::text = ANY ((ARRAY['ALWAYS'::character varying, 'NEVER'::character varying, 'WHEN'::character varying])::text[]))),
    CONSTRAINT kyc_fields_sensitivity_check CHECK (((sensitivity)::text = ANY ((ARRAY['NONE'::character varying, 'PII'::character varying, 'SECRET'::character varying])::text[])))
);


--
-- Name: kyc_list_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_list_items (
    id character varying(36) NOT NULL,
    list_code character varying(64) NOT NULL,
    value character varying(200) NOT NULL,
    label character varying(300) NOT NULL,
    parent_value character varying(200),
    attributes jsonb,
    sort integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL
);


--
-- Name: kyc_lists; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_lists (
    code character varying(64) NOT NULL,
    country character varying(2),
    description character varying(500)
);


--
-- Name: kyc_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_profiles (
    id character varying(36) NOT NULL,
    code character varying(100) NOT NULL,
    country character varying(2) NOT NULL,
    licence_type character varying(16) NOT NULL,
    subject_type character varying(16) NOT NULL,
    entity_type character varying(32),
    org_type character varying(16),
    schema_version_id character varying(36) NOT NULL,
    workflow_key character varying(64) NOT NULL,
    workflow_version integer DEFAULT 1 NOT NULL,
    workflow_config jsonb,
    collection_provider character varying(64) DEFAULT 'native-form'::character varying NOT NULL,
    check_providers jsonb,
    risk_scorer character varying(64) DEFAULT 'default'::character varying NOT NULL,
    params jsonb,
    ui_enabled boolean DEFAULT false NOT NULL,
    active boolean DEFAULT true NOT NULL,
    effective_from timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT kyc_profiles_licence_type_check CHECK (((licence_type)::text = ANY ((ARRAY['PSP'::character varying, 'EMI'::character varying])::text[]))),
    CONSTRAINT kyc_profiles_subject_type_check CHECK (((subject_type)::text = ANY ((ARRAY['BUSINESS'::character varying, 'INDIVIDUAL'::character varying])::text[])))
);


--
-- Name: kyc_provider_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_provider_events (
    id character varying(36) NOT NULL,
    provider character varying(64) NOT NULL,
    event_id character varying(120) NOT NULL,
    payload text,
    received_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    processed boolean DEFAULT false NOT NULL
);


--
-- Name: kyc_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_rules (
    id character varying(36) NOT NULL,
    schema_version_id character varying(36) NOT NULL,
    code character varying(100) NOT NULL,
    phase character varying(16) NOT NULL,
    validator_key character varying(64) NOT NULL,
    params jsonb,
    message character varying(500) NOT NULL,
    CONSTRAINT kyc_rules_phase_check CHECK (((phase)::text = ANY ((ARRAY['SAVE'::character varying, 'SUBMIT'::character varying])::text[])))
);


--
-- Name: kyc_schema_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_schema_versions (
    id character varying(36) NOT NULL,
    code character varying(100) NOT NULL,
    version integer NOT NULL,
    status character varying(16) NOT NULL,
    published_at timestamp(6) with time zone,
    CONSTRAINT kyc_schema_versions_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'RETIRED'::character varying])::text[])))
);


--
-- Name: kyc_search_index; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_search_index (
    application_id character varying(36) NOT NULL,
    canonical_tag character varying(64) NOT NULL,
    hmac character varying(64) NOT NULL
);


--
-- Name: kyc_sections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_sections (
    id character varying(36) NOT NULL,
    schema_version_id character varying(36) NOT NULL,
    key character varying(64) NOT NULL,
    title character varying(200) NOT NULL,
    step integer DEFAULT 0 NOT NULL,
    kind character varying(16) NOT NULL,
    min_items integer,
    max_items integer,
    visible_when jsonb,
    sort integer DEFAULT 0 NOT NULL,
    CONSTRAINT kyc_sections_kind_check CHECK (((kind)::text = ANY ((ARRAY['OBJECT'::character varying, 'LIST'::character varying, 'DOCUMENTS'::character varying])::text[])))
);


--
-- Name: kyc_verifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_verifications (
    id character varying(36) NOT NULL,
    application_id character varying(36) NOT NULL,
    submission_no integer NOT NULL,
    subject character varying(120),
    check_type character varying(32) NOT NULL,
    provider character varying(64) NOT NULL,
    provider_ref character varying(120),
    status character varying(16) NOT NULL,
    detail text,
    raw_response text,
    requested_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    requested_by character varying(36),
    completed_at timestamp(6) with time zone,
    note character varying(500)
);


--
-- Name: kyc_workflow_plugins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_workflow_plugins (
    interface_key character varying(64) NOT NULL,
    plugin_key character varying(64) NOT NULL,
    bean_name character varying(200) NOT NULL,
    description character varying(500),
    id character varying(36) NOT NULL
);


--
-- Name: kyc_workflow_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kyc_workflow_tasks (
    id character varying(36) NOT NULL,
    application_id character varying(36) NOT NULL,
    submission_no integer NOT NULL,
    task_kind character varying(16) NOT NULL,
    stage_key character varying(64) NOT NULL,
    stage_seq integer NOT NULL,
    actor_rule jsonb NOT NULL,
    status character varying(16) NOT NULL,
    outcome character varying(16),
    acted_by character varying(36),
    acted_at timestamp(6) with time zone,
    note character varying(1000),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT kyc_workflow_tasks_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'DONE'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT kyc_workflow_tasks_task_kind_check CHECK (((task_kind)::text = ANY ((ARRAY['ENTRY_CHECK'::character varying, 'STAGE'::character varying])::text[])))
);


--
-- Name: lender_credit_ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.lender_credit_ledger (
    amount numeric(18,2) NOT NULL,
    balance_after numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    advance_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    lender_id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    entry_type character varying(255) NOT NULL,
    CONSTRAINT lender_credit_ledger_entry_type_check CHECK (((entry_type)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying])::text[])))
);


--
-- Name: lenders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.lenders (
    collection_rate numeric(5,4),
    lender_limit numeric(18,2),
    max_daily_deduction numeric(18,2),
    service_fee_rate numeric(5,4),
    id character varying(36) NOT NULL,
    user_id character varying(36),
    licence_ref character varying(255),
    name character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    approval_status character varying(255),
    CONSTRAINT lenders_approval_status_check CHECK (((approval_status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'SUSPENDED'::character varying])::text[]))),
    CONSTRAINT lenders_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[])))
);


--
-- Name: limit_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.limit_profiles (
    advance_cap numeric(18,2),
    arrears_trigger_pct numeric(5,4),
    daily_debit_cap numeric(18,2),
    max_balance numeric(18,2),
    withdrawal_cap numeric(18,2),
    id character varying(36) NOT NULL,
    merchant_id character varying(36)
);


--
-- Name: merchant_subledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_subledger (
    amount numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    balance_account_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    issuer_ledger_id character varying(36),
    owner_id character varying(36) NOT NULL,
    source_ref_id character varying(36),
    currency character varying(255) NOT NULL,
    entry_type character varying(255) NOT NULL,
    owner_type character varying(255) NOT NULL,
    source character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT merchant_subledger_entry_type_check CHECK (((entry_type)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying])::text[]))),
    CONSTRAINT merchant_subledger_owner_type_check CHECK (((owner_type)::text = ANY ((ARRAY['MERCHANT'::character varying, 'SUPPLIER'::character varying])::text[]))),
    CONSTRAINT merchant_subledger_source_check CHECK (((source)::text = ANY ((ARRAY['PAYMENT'::character varying, 'CARD'::character varying, 'ADVANCE'::character varying, 'REPAYMENT'::character varying, 'WITHDRAWAL'::character varying, 'REFUND'::character varying, 'FEE'::character varying, 'SUPPLIER_PAYMENT'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT merchant_subledger_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_CONFIRMATION'::character varying, 'CONFIRMED'::character varying, 'REVERSED'::character varying])::text[])))
);


--
-- Name: merchants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchants (
    approved_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    approved_by character varying(36),
    id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    approval_status character varying(255),
    business_name character varying(255) NOT NULL,
    business_type character varying(255),
    kyc_status character varying(255),
    owner_id_number character varying(255),
    owner_name character varying(255),
    owner_phone character varying(255),
    registration_number character varying(255),
    kyc_risk_rating character varying(16),
    kyc_next_review_date date,
    CONSTRAINT merchants_approval_status_check CHECK (((approval_status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'SUSPENDED'::character varying])::text[]))),
    CONSTRAINT merchants_kyc_status_check CHECK (((kyc_status)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFIED'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: monitoring_metrics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.monitoring_metrics (
    metric_value numeric(18,4),
    recorded_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    metric_name character varying(255) NOT NULL
);


--
-- Name: notification_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_messages (
    id character varying(36) NOT NULL,
    channel character varying(10) NOT NULL,
    recipient character varying(255) NOT NULL,
    event_code character varying(40) NOT NULL,
    template_vars character varying(512),
    activation_token_id character varying(36),
    status character varying(16) DEFAULT 'QUEUED'::character varying NOT NULL,
    created_at timestamp(6) with time zone
);


--
-- Name: notification_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_templates (
    id character varying(36) NOT NULL,
    body character varying(500) NOT NULL,
    channel character varying(255) NOT NULL,
    event_code character varying(255) NOT NULL,
    locale character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT notification_templates_channel_check CHECK (((channel)::text = ANY ((ARRAY['SMS'::character varying, 'WHATSAPP'::character varying, 'SPEAKER'::character varying, 'EMAIL'::character varying, 'PUSH'::character varying])::text[]))),
    CONSTRAINT notification_templates_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DRAFT'::character varying])::text[])))
);


--
-- Name: onboarding_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.onboarding_requests (
    id character varying(36) NOT NULL,
    kind character varying(20) DEFAULT 'ORG_ONBOARDING'::character varying NOT NULL,
    status character varying(16) DEFAULT 'PENDING_REVIEW'::character varying NOT NULL,
    org_id character varying(36) NOT NULL,
    parent_org_id character varying(36),
    admin_user_id character varying(36),
    requested_by character varying(36) NOT NULL,
    requested_by_role character varying(36),
    requested_by_org_id character varying(36),
    reviewed_by character varying(36),
    reviewed_at timestamp(6) with time zone,
    note character varying(255),
    meta character varying(255),
    created_at timestamp(6) with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_onboarding_reviewer_not_requester CHECK (((reviewed_by IS NULL) OR ((reviewed_by)::text <> (requested_by)::text))),
    CONSTRAINT onboarding_requests_kind_check CHECK (((kind)::text = ANY ((ARRAY['ORG_ONBOARDING'::character varying, 'ADMIN_RECOVERY'::character varying])::text[]))),
    CONSTRAINT onboarding_requests_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_REVIEW'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'BLOCKED'::character varying])::text[])))
);


--
-- Name: order_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_items (
    quantity numeric(18,2) NOT NULL,
    unit_price numeric(18,2) NOT NULL,
    catalogue_item_id character varying(36),
    id character varying(36) NOT NULL,
    inventory_item_id character varying(36),
    order_id character varying(36) NOT NULL,
    product_name character varying(255) NOT NULL,
    unit character varying(255)
);


--
-- Name: orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orders (
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    supplier_id character varying(36) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT orders_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'INVOICED'::character varying, 'FULFILLED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: org_bank_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.org_bank_accounts (
    approved_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    approved_by character varying(36),
    id character varying(36) NOT NULL,
    requested_by character varying(36),
    org_id character varying(36) NOT NULL,
    account_holder character varying(255) NOT NULL,
    account_number character varying(255) NOT NULL,
    bank_name character varying(255) NOT NULL,
    request_note character varying(255),
    status character varying(255),
    CONSTRAINT chk_bank_approver_not_requester CHECK (((approved_by IS NULL) OR ((approved_by)::text <> (requested_by)::text))),
    CONSTRAINT supplier_bank_accounts_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: org_closures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.org_closures (
    ancestor_id character varying(36) NOT NULL,
    descendant_id character varying(36) NOT NULL,
    depth integer NOT NULL
);


--
-- Name: org_rights; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.org_rights (
    id character varying(36) NOT NULL,
    org_id character varying(36) NOT NULL,
    target_type character varying(16) NOT NULL,
    kind character varying(10) NOT NULL,
    granted_by character varying(36),
    granted_at timestamp(6) with time zone,
    CONSTRAINT org_rights_kind_check CHECK (((kind)::text = ANY ((ARRAY['DIRECT'::character varying, 'CEILING'::character varying])::text[]))),
    CONSTRAINT org_rights_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['MANUFACTURER'::character varying, 'DISTRIBUTOR'::character varying, 'SUPPLIER'::character varying, 'MERCHANT'::character varying, 'LENDER'::character varying])::text[])))
);


--
-- Name: org_role_ceilings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.org_role_ceilings (
    org_id character varying(36) NOT NULL,
    roles text NOT NULL,
    delegable_roles text DEFAULT ''::text NOT NULL,
    set_by character varying(36),
    set_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: organizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organizations (
    id character varying(36) NOT NULL,
    org_type character varying(16) NOT NULL,
    name character varying(255) NOT NULL,
    entity_id character varying(36),
    registration_number character varying(255),
    parent_id character varying(36),
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    onboarded_by character varying(36),
    onboarded_at timestamp(6) with time zone,
    created_by character varying(36),
    created_at timestamp(6) with time zone,
    version bigint DEFAULT 0 NOT NULL,
    kyc_status character varying(16),
    kyc_risk_rating character varying(16),
    kyc_next_review_date timestamp(6) with time zone,
    country character varying(2),
    licence_type character varying(8),
    mfa_required boolean DEFAULT false NOT NULL,
    CONSTRAINT organizations_kyc_risk_rating_check CHECK (((kyc_risk_rating)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying])::text[]))),
    CONSTRAINT organizations_kyc_status_check CHECK (((kyc_status)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFIED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT organizations_org_type_check CHECK (((org_type)::text = ANY ((ARRAY['PLATFORM'::character varying, 'MANUFACTURER'::character varying, 'DISTRIBUTOR'::character varying, 'SUPPLIER'::character varying, 'MERCHANT'::character varying, 'LENDER'::character varying])::text[]))),
    CONSTRAINT organizations_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REJECTED'::character varying, 'BLOCKED'::character varying])::text[])))
);


--
-- Name: payment_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_events (
    simulated boolean DEFAULT true,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    payment_id character varying(36) NOT NULL,
    event_type character varying(255) NOT NULL,
    meta character varying(500),
    payload_hash character varying(255),
    CONSTRAINT payment_events_event_type_check CHECK (((event_type)::text = ANY ((ARRAY['INITIATED'::character varying, 'CALLBACK'::character varying, 'CONFIRMED'::character varying, 'TIMEOUT'::character varying, 'FAILED'::character varying, 'REFUNDED'::character varying, 'DUPLICATE_IGNORED'::character varying])::text[])))
);


--
-- Name: payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payments (
    amount numeric(18,2) NOT NULL,
    item_quantity numeric(18,2),
    confirmed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    item_id character varying(36),
    merchant_id character varying(36) NOT NULL,
    channel character varying(255) NOT NULL,
    currency character varying(255) NOT NULL,
    idempotency_key character varying(255) NOT NULL,
    payer_ref character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT payments_channel_check CHECK (((channel)::text = ANY ((ARRAY['QR'::character varying, 'SHORT_CODE'::character varying, 'CARD'::character varying])::text[]))),
    CONSTRAINT payments_status_check CHECK (((status)::text = ANY ((ARRAY['INITIATED'::character varying, 'PENDING'::character varying, 'CONFIRMED'::character varying, 'FAILED'::character varying, 'REFUNDED'::character varying])::text[])))
);


--
-- Name: portfolio_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_snapshots (
    arrears_amount numeric(18,2),
    outstanding_principal numeric(18,2),
    snapshot_date date,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    lender_id character varying(36) NOT NULL
);


--
-- Name: qr_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qr_codes (
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    qr_payload character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT qr_codes_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ROTATED'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: reconciliation_breaks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reconciliation_breaks (
    amount numeric(18,2),
    created_at timestamp(6) with time zone,
    resolved_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    resolved_by character varying(36),
    run_id character varying(36) NOT NULL,
    break_type character varying(255) NOT NULL,
    description character varying(255),
    reference character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT reconciliation_breaks_break_type_check CHECK (((break_type)::text = ANY ((ARRAY['MISSING_CREDIT'::character varying, 'DUPLICATE_DEBIT'::character varying, 'STALE_HOLD'::character varying, 'ORPHAN_PAYMENT'::character varying])::text[]))),
    CONSTRAINT reconciliation_breaks_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'RESOLVED'::character varying])::text[])))
);


--
-- Name: reconciliation_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reconciliation_rules (
    tolerance numeric(18,2),
    id character varying(36) NOT NULL,
    break_category character varying(255),
    match_key character varying(255),
    name character varying(255) NOT NULL,
    schedule_cron character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT reconciliation_rules_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ROTATED'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: reconciliation_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reconciliation_runs (
    breaks_count integer,
    credit_ledger_total numeric(18,2),
    issuer_total numeric(18,2),
    run_date date NOT NULL,
    subledger_total numeric(18,2),
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT reconciliation_runs_status_check CHECK (((status)::text = ANY ((ARRAY['MATCHED'::character varying, 'BREAKS_FOUND'::character varying])::text[])))
);


--
-- Name: refunds; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refunds (
    amount numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    payment_id character varying(36) NOT NULL,
    reason character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT refunds_status_check CHECK (((status)::text = ANY ((ARRAY['REQUESTED'::character varying, 'PROCESSED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: reorder_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reorder_rules (
    order_quantity numeric(18,2),
    reorder_level numeric(18,2),
    id character varying(36) NOT NULL,
    item_id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    preferred_supplier_id character varying(36)
);


--
-- Name: restore_tests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restore_tests (
    ok boolean DEFAULT true,
    rpo_achieved_minutes integer,
    rto_achieved_minutes integer,
    tested_at timestamp(6) with time zone,
    backup_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    tested_by character varying(36)
);


--
-- Name: sar_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sar_reports (
    created_at timestamp(6) with time zone,
    submitted_at timestamp(6) with time zone,
    alert_id character varying(36),
    id character varying(36) NOT NULL,
    reported_by character varying(36),
    details text,
    reference character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT sar_reports_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'ACKNOWLEDGED'::character varying])::text[])))
);


--
-- Name: security_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.security_settings (
    updated_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    updated_by character varying(36),
    setting_key character varying(255) NOT NULL,
    setting_value character varying(255)
);


--
-- Name: settlements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.settlements (
    settlement_date date NOT NULL,
    total_amount numeric(18,2),
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    cycle character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT settlements_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SETTLED'::character varying])::text[])))
);


--
-- Name: short_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.short_codes (
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    short_code character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    CONSTRAINT short_codes_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ROTATED'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: sim_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sim_outbox (
    id character varying(36) NOT NULL,
    purpose character varying(40) NOT NULL,
    user_id character varying(36),
    activation_token_id character varying(36),
    raw_token character varying(128),
    meta character varying(255),
    created_at timestamp(6) with time zone
);


--
-- Name: simulation_scenarios; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.simulation_scenarios (
    enabled boolean DEFAULT true,
    is_labelled boolean DEFAULT true NOT NULL,
    id character varying(36) NOT NULL,
    action character varying(255) NOT NULL,
    connector_type character varying(255),
    description character varying(500),
    name character varying(255) NOT NULL,
    payload character varying(1000),
    CONSTRAINT simulation_scenarios_action_check CHECK (((action)::text = ANY ((ARRAY['CALLBACK'::character varying, 'TIMEOUT'::character varying, 'FAILURE'::character varying, 'DUPLICATE'::character varying, 'REFUND'::character varying, 'FUND_ADVANCE'::character varying])::text[]))),
    CONSTRAINT simulation_scenarios_connector_type_check CHECK (((connector_type)::text = ANY ((ARRAY['BANK_ISSUER'::character varying, 'LENDER'::character varying, 'IPS'::character varying, 'CARD'::character varying, 'QR'::character varying, 'KYC'::character varying, 'NOTIFICATION'::character varying])::text[])))
);


--
-- Name: statements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.statements (
    period_end date NOT NULL,
    period_start date NOT NULL,
    generated_at timestamp(6) with time zone,
    generated_by character varying(36),
    id character varying(36) NOT NULL,
    owner_id character varying(36) NOT NULL,
    file_ref character varying(255),
    owner_type character varying(255) NOT NULL
);


--
-- Name: stock_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_alerts (
    resolved boolean DEFAULT false,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    item_id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    alert_type character varying(255) NOT NULL,
    CONSTRAINT stock_alerts_alert_type_check CHECK (((alert_type)::text = ANY ((ARRAY['LOW_STOCK'::character varying, 'OUT_OF_STOCK'::character varying])::text[])))
);


--
-- Name: stock_movements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_movements (
    quantity numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    item_id character varying(36) NOT NULL,
    movement_type character varying(255) NOT NULL,
    reference character varying(255),
    CONSTRAINT stock_movements_movement_type_check CHECK (((movement_type)::text = ANY ((ARRAY['SALE'::character varying, 'DELIVERY'::character varying, 'ADJUSTMENT'::character varying, 'WASTE'::character varying])::text[])))
);


--
-- Name: supplier_catalogue_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_catalogue_items (
    unit_price numeric(18,2),
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    supplier_id character varying(36) NOT NULL,
    product_name character varying(255) NOT NULL,
    status character varying(255),
    unit character varying(255),
    CONSTRAINT supplier_catalogue_items_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'RETIRED'::character varying])::text[])))
);


--
-- Name: suppliers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.suppliers (
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    approval_status character varying(255),
    business_name character varying(255) NOT NULL,
    owner_name character varying(255),
    owner_phone character varying(255),
    registration_number character varying(255),
    kyc_risk_rating character varying(16),
    kyc_next_review_date date,
    supplier_type character varying(255),
    owner_id_number character varying(255),
    approved_by character varying(36),
    approved_at timestamp(6) with time zone,
    CONSTRAINT suppliers_approval_status_check CHECK (((approval_status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'SUSPENDED'::character varying])::text[])))
);


--
-- Name: suspensions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.suspensions (
    lifted_at timestamp(6) with time zone,
    suspended_at timestamp(6) with time zone,
    entity_id character varying(36) NOT NULL,
    id character varying(36) NOT NULL,
    suspended_by character varying(36),
    entity_type character varying(255) NOT NULL,
    reason character varying(255) NOT NULL,
    org_id character varying(36),
    suspended_by_org_id character varying(36),
    lifted_by character varying(36),
    CONSTRAINT chk_suspension_entity_type CHECK (((entity_type)::text = ANY ((ARRAY['MERCHANT'::character varying, 'SUPPLIER'::character varying, 'LENDER'::character varying, 'ORG'::character varying])::text[])))
);


--
-- Name: tab_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tab_entries (
    amount numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    tab_id character varying(36) NOT NULL,
    description character varying(255)
);


--
-- Name: tabs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tabs (
    due_date date,
    total_amount numeric(18,2) NOT NULL,
    created_at timestamp(6) with time zone,
    settled_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    customer_name character varying(255) NOT NULL,
    customer_phone character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT tabs_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'PAID'::character varying, 'OVERDUE'::character varying, 'WRITTEN_OFF'::character varying])::text[])))
);


--
-- Name: test_data_sets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.test_data_sets (
    labelled_synthetic boolean DEFAULT true,
    record_count integer,
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    description character varying(1000),
    name character varying(255) NOT NULL
);


--
-- Name: user_platform_functions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_platform_functions (
    user_id character varying(36) NOT NULL,
    function character varying(32) NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    created_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    email character varying(255),
    name character varying(255),
    password_hash character varying(255),
    phone character varying(255) NOT NULL,
    role character varying(255) NOT NULL,
    status character varying(255),
    org_id character varying(36) NOT NULL,
    org_role character varying(16),
    username character varying(32),
    created_by character varying(36),
    activated_at timestamp(6) with time zone,
    CONSTRAINT chk_users_role CHECK (((role)::text = ANY ((ARRAY['MERCHANT'::character varying, 'SUPPLIER'::character varying, 'LENDER'::character varying, 'MANUFACTURER'::character varying, 'DISTRIBUTOR'::character varying, 'TPTS_OPS'::character varying, 'MINDLABS_ADMIN'::character varying])::text[]))),
    CONSTRAINT chk_users_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'ARCHIVED'::character varying])::text[])))
);


--
-- Name: vintage_analysis; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vintage_analysis (
    cohort_month date,
    loss_rate numeric(5,4),
    losses numeric(18,2),
    originations numeric(18,2),
    id character varying(36) NOT NULL,
    lender_id character varying(36) NOT NULL
);


--
-- Name: withdrawals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.withdrawals (
    amount numeric(18,2) NOT NULL,
    processed_at timestamp(6) with time zone,
    requested_at timestamp(6) with time zone,
    id character varying(36) NOT NULL,
    merchant_id character varying(36) NOT NULL,
    bank_reference character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT withdrawals_status_check CHECK (((status)::text = ANY ((ARRAY['REQUESTED'::character varying, 'APPROVED'::character varying, 'PAID'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: account_mappings account_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_mappings
    ADD CONSTRAINT account_mappings_pkey PRIMARY KEY (id);


--
-- Name: activation_tokens activation_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activation_tokens
    ADD CONSTRAINT activation_tokens_pkey PRIMARY KEY (id);


--
-- Name: activation_tokens activation_tokens_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activation_tokens
    ADD CONSTRAINT activation_tokens_token_hash_key UNIQUE (token_hash);


--
-- Name: advance_repayments advance_repayments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_repayments
    ADD CONSTRAINT advance_repayments_pkey PRIMARY KEY (id);


--
-- Name: advances advances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advances
    ADD CONSTRAINT advances_pkey PRIMARY KEY (id);


--
-- Name: aml_alerts aml_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aml_alerts
    ADD CONSTRAINT aml_alerts_pkey PRIMARY KEY (id);


--
-- Name: api_clients api_clients_client_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_clients
    ADD CONSTRAINT api_clients_client_id_key UNIQUE (client_id);


--
-- Name: api_clients api_clients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_clients
    ADD CONSTRAINT api_clients_pkey PRIMARY KEY (id);


--
-- Name: arrears_cases arrears_cases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.arrears_cases
    ADD CONSTRAINT arrears_cases_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: auth_events_seen auth_events_seen_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_events_seen
    ADD CONSTRAINT auth_events_seen_pkey PRIMARY KEY (event_id);


--
-- Name: auth_outbox auth_outbox_dedupe_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_outbox
    ADD CONSTRAINT auth_outbox_dedupe_key_key UNIQUE (dedupe_key);


--
-- Name: auth_outbox auth_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_outbox
    ADD CONSTRAINT auth_outbox_pkey PRIMARY KEY (id);


--
-- Name: backups backups_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backups
    ADD CONSTRAINT backups_pkey PRIMARY KEY (id);


--
-- Name: balance_accounts balance_accounts_owner_type_owner_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.balance_accounts
    ADD CONSTRAINT balance_accounts_owner_type_owner_id_key UNIQUE (owner_type, owner_id);


--
-- Name: balance_accounts balance_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.balance_accounts
    ADD CONSTRAINT balance_accounts_pkey PRIMARY KEY (id);


--
-- Name: balance_holds balance_holds_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.balance_holds
    ADD CONSTRAINT balance_holds_pkey PRIMARY KEY (id);


--
-- Name: card_terminals card_terminals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_terminals
    ADD CONSTRAINT card_terminals_pkey PRIMARY KEY (id);


--
-- Name: complaints complaints_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.complaints
    ADD CONSTRAINT complaints_pkey PRIMARY KEY (id);


--
-- Name: consent_withdrawals consent_withdrawals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_withdrawals
    ADD CONSTRAINT consent_withdrawals_pkey PRIMARY KEY (id);


--
-- Name: credit_assessments credit_assessments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_assessments
    ADD CONSTRAINT credit_assessments_pkey PRIMARY KEY (id);


--
-- Name: credit_consents credit_consents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_consents
    ADD CONSTRAINT credit_consents_pkey PRIMARY KEY (id);


--
-- Name: deployments deployments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deployments
    ADD CONSTRAINT deployments_pkey PRIMARY KEY (id);


--
-- Name: devices_sessions devices_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices_sessions
    ADD CONSTRAINT devices_sessions_pkey PRIMARY KEY (id);


--
-- Name: disputes disputes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disputes
    ADD CONSTRAINT disputes_pkey PRIMARY KEY (id);


--
-- Name: dsar_requests dsar_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dsar_requests
    ADD CONSTRAINT dsar_requests_pkey PRIMARY KEY (id);


--
-- Name: eligibility_rules eligibility_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.eligibility_rules
    ADD CONSTRAINT eligibility_rules_pkey PRIMARY KEY (id);


--
-- Name: eligibility_rules eligibility_rules_rule_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.eligibility_rules
    ADD CONSTRAINT eligibility_rules_rule_code_key UNIQUE (rule_code);


--
-- Name: fee_reports fee_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_reports
    ADD CONSTRAINT fee_reports_pkey PRIMARY KEY (id);


--
-- Name: fee_tariffs fee_tariffs_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_tariffs
    ADD CONSTRAINT fee_tariffs_code_key UNIQUE (code);


--
-- Name: fee_tariffs fee_tariffs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_tariffs
    ADD CONSTRAINT fee_tariffs_pkey PRIMARY KEY (id);


--
-- Name: fee_transactions fee_transactions_fee_code_reference_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_transactions
    ADD CONSTRAINT fee_transactions_fee_code_reference_key UNIQUE (fee_code, reference);


--
-- Name: fee_transactions fee_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_transactions
    ADD CONSTRAINT fee_transactions_pkey PRIMARY KEY (id);


--
-- Name: identity_links identity_links_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.identity_links
    ADD CONSTRAINT identity_links_pkey PRIMARY KEY (id);


--
-- Name: incidents incidents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.incidents
    ADD CONSTRAINT incidents_pkey PRIMARY KEY (id);


--
-- Name: integration_connectors integration_connectors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_connectors
    ADD CONSTRAINT integration_connectors_pkey PRIMARY KEY (id);


--
-- Name: inventory_items inventory_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_items
    ADD CONSTRAINT inventory_items_pkey PRIMARY KEY (id);


--
-- Name: invoice_payments invoice_payments_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoice_payments
    ADD CONSTRAINT invoice_payments_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: invoice_payments invoice_payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoice_payments
    ADD CONSTRAINT invoice_payments_pkey PRIMARY KEY (id);


--
-- Name: invoices invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);


--
-- Name: invoices invoices_unique_reference_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_unique_reference_key UNIQUE (unique_reference);


--
-- Name: issuer_balance_ledger issuer_balance_ledger_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issuer_balance_ledger
    ADD CONSTRAINT issuer_balance_ledger_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: issuer_balance_ledger issuer_balance_ledger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.issuer_balance_ledger
    ADD CONSTRAINT issuer_balance_ledger_pkey PRIMARY KEY (id);


--
-- Name: khata_accounts khata_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.khata_accounts
    ADD CONSTRAINT khata_accounts_pkey PRIMARY KEY (id);


--
-- Name: khata_entries khata_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.khata_entries
    ADD CONSTRAINT khata_entries_pkey PRIMARY KEY (id);


--
-- Name: kyc_application_submissions kyc_application_submissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_application_submissions
    ADD CONSTRAINT kyc_application_submissions_pkey PRIMARY KEY (id);


--
-- Name: kyc_applications kyc_applications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_applications
    ADD CONSTRAINT kyc_applications_pkey PRIMARY KEY (id);


--
-- Name: kyc_checks kyc_checks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_checks
    ADD CONSTRAINT kyc_checks_pkey PRIMARY KEY (id);


--
-- Name: kyc_fields kyc_fields_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_fields
    ADD CONSTRAINT kyc_fields_pkey PRIMARY KEY (id);


--
-- Name: kyc_list_items kyc_list_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_list_items
    ADD CONSTRAINT kyc_list_items_pkey PRIMARY KEY (id);


--
-- Name: kyc_lists kyc_lists_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_lists
    ADD CONSTRAINT kyc_lists_pkey PRIMARY KEY (code);


--
-- Name: kyc_profiles kyc_profiles_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_profiles
    ADD CONSTRAINT kyc_profiles_code_key UNIQUE (code);


--
-- Name: kyc_profiles kyc_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_profiles
    ADD CONSTRAINT kyc_profiles_pkey PRIMARY KEY (id);


--
-- Name: kyc_provider_events kyc_provider_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_provider_events
    ADD CONSTRAINT kyc_provider_events_pkey PRIMARY KEY (id);


--
-- Name: kyc_rules kyc_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_rules
    ADD CONSTRAINT kyc_rules_pkey PRIMARY KEY (id);


--
-- Name: kyc_schema_versions kyc_schema_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_schema_versions
    ADD CONSTRAINT kyc_schema_versions_pkey PRIMARY KEY (id);


--
-- Name: kyc_sections kyc_sections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_sections
    ADD CONSTRAINT kyc_sections_pkey PRIMARY KEY (id);


--
-- Name: kyc_verifications kyc_verifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_verifications
    ADD CONSTRAINT kyc_verifications_pkey PRIMARY KEY (id);


--
-- Name: kyc_workflow_plugins kyc_workflow_plugins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_workflow_plugins
    ADD CONSTRAINT kyc_workflow_plugins_pkey PRIMARY KEY (id);


--
-- Name: kyc_workflow_tasks kyc_workflow_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_workflow_tasks
    ADD CONSTRAINT kyc_workflow_tasks_pkey PRIMARY KEY (id);


--
-- Name: lender_credit_ledger lender_credit_ledger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lender_credit_ledger
    ADD CONSTRAINT lender_credit_ledger_pkey PRIMARY KEY (id);


--
-- Name: lenders lenders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lenders
    ADD CONSTRAINT lenders_pkey PRIMARY KEY (id);


--
-- Name: limit_profiles limit_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.limit_profiles
    ADD CONSTRAINT limit_profiles_pkey PRIMARY KEY (id);


--
-- Name: merchant_subledger merchant_subledger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_subledger
    ADD CONSTRAINT merchant_subledger_pkey PRIMARY KEY (id);


--
-- Name: merchants merchants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchants
    ADD CONSTRAINT merchants_pkey PRIMARY KEY (id);


--
-- Name: monitoring_metrics monitoring_metrics_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monitoring_metrics
    ADD CONSTRAINT monitoring_metrics_pkey PRIMARY KEY (id);


--
-- Name: notification_messages notification_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_messages
    ADD CONSTRAINT notification_messages_pkey PRIMARY KEY (id);


--
-- Name: notification_templates notification_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_templates
    ADD CONSTRAINT notification_templates_pkey PRIMARY KEY (id);


--
-- Name: onboarding_requests onboarding_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.onboarding_requests
    ADD CONSTRAINT onboarding_requests_pkey PRIMARY KEY (id);


--
-- Name: order_items order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (id);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: org_rights org_rights_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_rights
    ADD CONSTRAINT org_rights_pkey PRIMARY KEY (id);


--
-- Name: org_role_ceilings org_role_ceilings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_role_ceilings
    ADD CONSTRAINT org_role_ceilings_pkey PRIMARY KEY (org_id);


--
-- Name: organizations organizations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);


--
-- Name: payment_events payment_events_payment_id_event_type_payload_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_events
    ADD CONSTRAINT payment_events_payment_id_event_type_payload_hash_key UNIQUE (payment_id, event_type, payload_hash);


--
-- Name: payment_events payment_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_events
    ADD CONSTRAINT payment_events_pkey PRIMARY KEY (id);


--
-- Name: payments payments_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- Name: org_closures pk_closure; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_closures
    ADD CONSTRAINT pk_closure PRIMARY KEY (ancestor_id, descendant_id);


--
-- Name: kyc_search_index pk_kyc_search_index; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_search_index
    ADD CONSTRAINT pk_kyc_search_index PRIMARY KEY (application_id, canonical_tag, hmac);


--
-- Name: user_platform_functions pk_user_platform_functions; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_platform_functions
    ADD CONSTRAINT pk_user_platform_functions PRIMARY KEY (user_id, function);


--
-- Name: portfolio_snapshots portfolio_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_snapshots
    ADD CONSTRAINT portfolio_snapshots_pkey PRIMARY KEY (id);


--
-- Name: qr_codes qr_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qr_codes
    ADD CONSTRAINT qr_codes_pkey PRIMARY KEY (id);


--
-- Name: reconciliation_breaks reconciliation_breaks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_breaks
    ADD CONSTRAINT reconciliation_breaks_pkey PRIMARY KEY (id);


--
-- Name: reconciliation_rules reconciliation_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_rules
    ADD CONSTRAINT reconciliation_rules_pkey PRIMARY KEY (id);


--
-- Name: reconciliation_runs reconciliation_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation_runs
    ADD CONSTRAINT reconciliation_runs_pkey PRIMARY KEY (id);


--
-- Name: refunds refunds_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refunds
    ADD CONSTRAINT refunds_pkey PRIMARY KEY (id);


--
-- Name: reorder_rules reorder_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reorder_rules
    ADD CONSTRAINT reorder_rules_pkey PRIMARY KEY (id);


--
-- Name: restore_tests restore_tests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restore_tests
    ADD CONSTRAINT restore_tests_pkey PRIMARY KEY (id);


--
-- Name: sar_reports sar_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sar_reports
    ADD CONSTRAINT sar_reports_pkey PRIMARY KEY (id);


--
-- Name: security_settings security_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.security_settings
    ADD CONSTRAINT security_settings_pkey PRIMARY KEY (id);


--
-- Name: security_settings security_settings_setting_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.security_settings
    ADD CONSTRAINT security_settings_setting_key_key UNIQUE (setting_key);


--
-- Name: settlements settlements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.settlements
    ADD CONSTRAINT settlements_pkey PRIMARY KEY (id);


--
-- Name: short_codes short_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.short_codes
    ADD CONSTRAINT short_codes_pkey PRIMARY KEY (id);


--
-- Name: short_codes short_codes_short_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.short_codes
    ADD CONSTRAINT short_codes_short_code_key UNIQUE (short_code);


--
-- Name: sim_outbox sim_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_outbox
    ADD CONSTRAINT sim_outbox_pkey PRIMARY KEY (id);


--
-- Name: simulation_scenarios simulation_scenarios_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.simulation_scenarios
    ADD CONSTRAINT simulation_scenarios_pkey PRIMARY KEY (id);


--
-- Name: statements statements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.statements
    ADD CONSTRAINT statements_pkey PRIMARY KEY (id);


--
-- Name: stock_alerts stock_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alerts
    ADD CONSTRAINT stock_alerts_pkey PRIMARY KEY (id);


--
-- Name: stock_movements stock_movements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT stock_movements_pkey PRIMARY KEY (id);


--
-- Name: org_bank_accounts supplier_bank_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_bank_accounts
    ADD CONSTRAINT supplier_bank_accounts_pkey PRIMARY KEY (id);


--
-- Name: supplier_catalogue_items supplier_catalogue_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_catalogue_items
    ADD CONSTRAINT supplier_catalogue_items_pkey PRIMARY KEY (id);


--
-- Name: suppliers suppliers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.suppliers
    ADD CONSTRAINT suppliers_pkey PRIMARY KEY (id);


--
-- Name: suspensions suspensions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.suspensions
    ADD CONSTRAINT suspensions_pkey PRIMARY KEY (id);


--
-- Name: tab_entries tab_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tab_entries
    ADD CONSTRAINT tab_entries_pkey PRIMARY KEY (id);


--
-- Name: tabs tabs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tabs
    ADD CONSTRAINT tabs_pkey PRIMARY KEY (id);


--
-- Name: test_data_sets test_data_sets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.test_data_sets
    ADD CONSTRAINT test_data_sets_pkey PRIMARY KEY (id);


--
-- Name: identity_links uq_identity_issuer_subject; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.identity_links
    ADD CONSTRAINT uq_identity_issuer_subject UNIQUE (issuer, subject);


--
-- Name: kyc_fields uq_kyc_field_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_fields
    ADD CONSTRAINT uq_kyc_field_key UNIQUE (section_id, key);


--
-- Name: kyc_list_items uq_kyc_list_item; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_list_items
    ADD CONSTRAINT uq_kyc_list_item UNIQUE (list_code, value);


--
-- Name: kyc_workflow_plugins uq_kyc_plugin; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_workflow_plugins
    ADD CONSTRAINT uq_kyc_plugin UNIQUE (interface_key, plugin_key);


--
-- Name: kyc_provider_events uq_kyc_provider_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_provider_events
    ADD CONSTRAINT uq_kyc_provider_event UNIQUE (provider, event_id);


--
-- Name: kyc_rules uq_kyc_rule_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_rules
    ADD CONSTRAINT uq_kyc_rule_code UNIQUE (schema_version_id, code);


--
-- Name: kyc_schema_versions uq_kyc_schema_code_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_schema_versions
    ADD CONSTRAINT uq_kyc_schema_code_version UNIQUE (code, version);


--
-- Name: kyc_sections uq_kyc_section_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_sections
    ADD CONSTRAINT uq_kyc_section_key UNIQUE (schema_version_id, key);


--
-- Name: kyc_application_submissions uq_kyc_submission; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_application_submissions
    ADD CONSTRAINT uq_kyc_submission UNIQUE (application_id, submission_no);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: vintage_analysis vintage_analysis_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vintage_analysis
    ADD CONSTRAINT vintage_analysis_pkey PRIMARY KEY (id);


--
-- Name: withdrawals withdrawals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawals
    ADD CONSTRAINT withdrawals_pkey PRIMARY KEY (id);


--
-- Name: idx3ct1lc145ugvl2vl8o84bsrno; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx3ct1lc145ugvl2vl8o84bsrno ON public.advances USING btree (lender_id);


--
-- Name: idx4y9xvvmmwe9k17kqw9mrdducl; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx4y9xvvmmwe9k17kqw9mrdducl ON public.issuer_balance_ledger USING btree (owner_id);


--
-- Name: idx56bccfeoti66mnnu76umdya26; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx56bccfeoti66mnnu76umdya26 ON public.merchant_subledger USING btree (source_ref_id);


--
-- Name: idx7dob0hq2p5cbbv97axaqgplnb; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx7dob0hq2p5cbbv97axaqgplnb ON public.inventory_items USING btree (merchant_id);


--
-- Name: idx841ym1vmrfcryli4xi8jqfxyu; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx841ym1vmrfcryli4xi8jqfxyu ON public.payment_events USING btree (payment_id);


--
-- Name: idx8n4qlcolxwvy3f2tik4gnmerx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx8n4qlcolxwvy3f2tik4gnmerx ON public.merchant_subledger USING btree (owner_id);


--
-- Name: idx8uj2iwrtn1nv8bajtk8u93sx8; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx8uj2iwrtn1nv8bajtk8u93sx8 ON public.advance_repayments USING btree (advance_id);


--
-- Name: idx991mtsx8uaq411yokk8d29rkn; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx991mtsx8uaq411yokk8d29rkn ON public.balance_holds USING btree (merchant_id);


--
-- Name: idx9gap2fmw66v092ntb58rtohwh; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx9gap2fmw66v092ntb58rtohwh ON public.order_items USING btree (order_id);


--
-- Name: idxau2nabxhi7msgs09bqyq8ngus; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxau2nabxhi7msgs09bqyq8ngus ON public.monitoring_metrics USING btree (metric_name);


--
-- Name: idxavndqymppt18uo2llo57ln1xx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxavndqymppt18uo2llo57ln1xx ON public.lender_credit_ledger USING btree (advance_id);


--
-- Name: idxb1ima1h6lxnllh9aunsdcle9j; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxb1ima1h6lxnllh9aunsdcle9j ON public.advances USING btree (merchant_id);


--
-- Name: idxc2f7fsika7fjcdgl2laf8sykn; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxc2f7fsika7fjcdgl2laf8sykn ON public.invoice_payments USING btree (invoice_id);


--
-- Name: idxc5tdsn03s6d46388rrtx4vrh4; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxc5tdsn03s6d46388rrtx4vrh4 ON public.advance_repayments USING btree (merchant_id);


--
-- Name: idxctx6v6giftswa495ahhm3qnra; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxctx6v6giftswa495ahhm3qnra ON public.stock_alerts USING btree (merchant_id);


--
-- Name: idxd0t8boqh27xx1ouoe6m5uxo8n; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxd0t8boqh27xx1ouoe6m5uxo8n ON public.stock_movements USING btree (item_id);


--
-- Name: idxd5h6nx1lpuert63h5qpjc3efq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxd5h6nx1lpuert63h5qpjc3efq ON public.credit_assessments USING btree (merchant_id);


--
-- Name: idxdjntwwdmfuwfpiiwdsqo9r192; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxdjntwwdmfuwfpiiwdsqo9r192 ON public.reconciliation_breaks USING btree (run_id);


--
-- Name: idxe12cg671snfh7iaefyy7lg4i6; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxe12cg671snfh7iaefyy7lg4i6 ON public.invoices USING btree (supplier_id);


--
-- Name: idxemfeurug21fv1stnxlakggj9a; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxemfeurug21fv1stnxlakggj9a ON public.withdrawals USING btree (merchant_id);


--
-- Name: idxglq6d6w5csjflwdly3v27b1qg; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxglq6d6w5csjflwdly3v27b1qg ON public.invoice_payments USING btree (merchant_id);


--
-- Name: idxh570cdj7423ffvxr2vulqkh3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxh570cdj7423ffvxr2vulqkh3 ON public.payments USING btree (status);


--
-- Name: idxhs1p265cc6vby3d2ybqpooy83; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhs1p265cc6vby3d2ybqpooy83 ON public.tab_entries USING btree (tab_id);


--
-- Name: idxhxmf98fkeqxfh9yxgc9uimwvr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhxmf98fkeqxfh9yxgc9uimwvr ON public.orders USING btree (merchant_id);


--
-- Name: idxj594m6kn94ndwbari67ftlhop; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxj594m6kn94ndwbari67ftlhop ON public.reorder_rules USING btree (merchant_id);


--
-- Name: idxjg9c24tc5gj20v8ime1x27252; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxjg9c24tc5gj20v8ime1x27252 ON public.tabs USING btree (merchant_id);


--
-- Name: idxjw2330eitrvophdsls1xdtv4r; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxjw2330eitrvophdsls1xdtv4r ON public.khata_accounts USING btree (merchant_id);


--
-- Name: idxl8mus5nda5anlgvjnu8dfg0i8; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxl8mus5nda5anlgvjnu8dfg0i8 ON public.refunds USING btree (payment_id);


--
-- Name: idxlrmog08eb3sb89wl6v1guvij2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxlrmog08eb3sb89wl6v1guvij2 ON public.fee_transactions USING btree (merchant_id);


--
-- Name: idxmkfw1a30lwva24di2qs59poj6; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxmkfw1a30lwva24di2qs59poj6 ON public.invoices USING btree (merchant_id);


--
-- Name: idxorgr6cp4uqwv2cqa0twv15qvy; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxorgr6cp4uqwv2cqa0twv15qvy ON public.khata_entries USING btree (khata_id);


--
-- Name: idxpswugsrq1k7ibjf1ss0ahf9ly; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxpswugsrq1k7ibjf1ss0ahf9ly ON public.credit_consents USING btree (merchant_id);


--
-- Name: idxrkggtkak4xiot4ec0g9l4wxq6; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxrkggtkak4xiot4ec0g9l4wxq6 ON public.payments USING btree (merchant_id);


--
-- Name: idxs4q5g32sqdc3m9dlfthl4tjnq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxs4q5g32sqdc3m9dlfthl4tjnq ON public.orders USING btree (supplier_id);


--
-- Name: ix_act_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_act_user ON public.activation_tokens USING btree (user_id, used_at);


--
-- Name: ix_auth_outbox_ready; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_auth_outbox_ready ON public.auth_outbox USING btree (state, available_at);


--
-- Name: ix_closure_desc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_closure_desc ON public.org_closures USING btree (descendant_id);


--
-- Name: ix_identity_links_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_identity_links_user ON public.identity_links USING btree (user_id);


--
-- Name: ix_kyc_app_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_app_parent ON public.kyc_applications USING btree (parent_org_id);


--
-- Name: ix_kyc_app_profile; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_app_profile ON public.kyc_applications USING btree (profile_id);


--
-- Name: ix_kyc_app_schema; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_app_schema ON public.kyc_applications USING btree (schema_version_id);


--
-- Name: ix_kyc_app_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_app_status ON public.kyc_applications USING btree (status);


--
-- Name: ix_kyc_app_suspended; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_app_suspended ON public.kyc_applications USING btree (is_suspended) WHERE is_suspended;


--
-- Name: ix_kyc_fields_section; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_fields_section ON public.kyc_fields USING btree (section_id, sort);


--
-- Name: ix_kyc_fields_tag; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_fields_tag ON public.kyc_fields USING btree (canonical_tag);


--
-- Name: ix_kyc_list_items_list; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_list_items_list ON public.kyc_list_items USING btree (list_code, active, sort);


--
-- Name: ix_kyc_list_items_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_list_items_parent ON public.kyc_list_items USING btree (list_code, parent_value);


--
-- Name: ix_kyc_profile_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_profile_lookup ON public.kyc_profiles USING btree (country, licence_type, subject_type, active);


--
-- Name: ix_kyc_rules_schema; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_rules_schema ON public.kyc_rules USING btree (schema_version_id, phase);


--
-- Name: ix_kyc_schema_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_schema_status ON public.kyc_schema_versions USING btree (status);


--
-- Name: ix_kyc_sections_schema; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_sections_schema ON public.kyc_sections USING btree (schema_version_id, sort);


--
-- Name: ix_kyc_tasks_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_tasks_open ON public.kyc_workflow_tasks USING btree (application_id, submission_no, status);


--
-- Name: ix_kyc_tasks_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_tasks_status ON public.kyc_workflow_tasks USING btree (status, stage_key);


--
-- Name: ix_kyc_verifications_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_kyc_verifications_app ON public.kyc_verifications USING btree (application_id, submission_no, check_type);


--
-- Name: ix_onb_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_onb_org ON public.onboarding_requests USING btree (org_id);


--
-- Name: ix_onb_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_onb_status ON public.onboarding_requests USING btree (status, created_at);


--
-- Name: ix_org_kyc_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_org_kyc_status ON public.organizations USING btree (kyc_status);


--
-- Name: ix_org_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_org_parent ON public.organizations USING btree (parent_id);


--
-- Name: ix_org_type_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_org_type_status ON public.organizations USING btree (org_type, status);


--
-- Name: ix_rights_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rights_org ON public.org_rights USING btree (org_id);


--
-- Name: ix_suspensions_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_suspensions_org ON public.suspensions USING btree (org_id, lifted_at);


--
-- Name: ix_users_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_users_org ON public.users USING btree (org_id);


--
-- Name: ix_users_phone; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_users_phone ON public.users USING btree (phone);


--
-- Name: uq_identity_trustplane_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_identity_trustplane_user ON public.identity_links USING btree (user_id) WHERE (((provider)::text = 'trustplane'::text) AND ((status)::text <> 'REVOKED'::text));


--
-- Name: uq_kyc_app_open; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_kyc_app_open ON public.kyc_applications USING btree (org_id) WHERE ((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ENTRY_CHECK'::character varying, 'VERIFYING'::character varying, 'IN_REVIEW'::character varying])::text[]));


--
-- Name: uq_kyc_profile_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_kyc_profile_active ON public.kyc_profiles USING btree (country, licence_type, subject_type, entity_type, org_type) WHERE active;


--
-- Name: uq_kyc_task_actor; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_kyc_task_actor ON public.kyc_workflow_tasks USING btree (application_id, submission_no, acted_by) WHERE (acted_by IS NOT NULL);


--
-- Name: uq_kyc_verification; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_kyc_verification ON public.kyc_verifications USING btree (application_id, submission_no, check_type, COALESCE(subject, ''::character varying));


--
-- Name: uq_org_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_org_entity ON public.organizations USING btree (org_type, entity_id) WHERE (entity_id IS NOT NULL);


--
-- Name: uq_org_platform; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_org_platform ON public.organizations USING btree (org_type) WHERE (((org_type)::text = 'PLATFORM'::text) AND (entity_id IS NULL));


--
-- Name: uq_rights; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_rights ON public.org_rights USING btree (org_id, target_type, kind);


--
-- Name: uq_users_username; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_username ON public.users USING btree (username) WHERE (username IS NOT NULL);


--
-- Name: auth_outbox auth_outbox_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_outbox
    ADD CONSTRAINT auth_outbox_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: advance_repayments fk8x7leghnq1470jub41feeadb9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advance_repayments
    ADD CONSTRAINT fk8x7leghnq1470jub41feeadb9 FOREIGN KEY (advance_id) REFERENCES public.advances(id);


--
-- Name: org_closures fk_closure_anc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_closures
    ADD CONSTRAINT fk_closure_anc FOREIGN KEY (ancestor_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: org_closures fk_closure_desc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_closures
    ADD CONSTRAINT fk_closure_desc FOREIGN KEY (descendant_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: onboarding_requests fk_onb_org; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.onboarding_requests
    ADD CONSTRAINT fk_onb_org FOREIGN KEY (org_id) REFERENCES public.organizations(id);


--
-- Name: org_bank_accounts fk_org_bank_accounts_org; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_bank_accounts
    ADD CONSTRAINT fk_org_bank_accounts_org FOREIGN KEY (org_id) REFERENCES public.organizations(id);


--
-- Name: organizations fk_org_parent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT fk_org_parent FOREIGN KEY (parent_id) REFERENCES public.organizations(id);


--
-- Name: org_rights fk_rights_org; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_rights
    ADD CONSTRAINT fk_rights_org FOREIGN KEY (org_id) REFERENCES public.organizations(id);


--
-- Name: advances fka7ad66toe0gx0xiwlrpbqk258; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advances
    ADD CONSTRAINT fka7ad66toe0gx0xiwlrpbqk258 FOREIGN KEY (merchant_id) REFERENCES public.merchants(id);


--
-- Name: invoices fkaefpcn4ex6dbrkc9qgy573rij; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT fkaefpcn4ex6dbrkc9qgy573rij FOREIGN KEY (merchant_id) REFERENCES public.merchants(id);


--
-- Name: credit_assessments fkas53c2irt82faev5yt9jits0t; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_assessments
    ADD CONSTRAINT fkas53c2irt82faev5yt9jits0t FOREIGN KEY (lender_id) REFERENCES public.lenders(id);


--
-- Name: payments fkb9a63y4883txc4u09igbu77g6; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fkb9a63y4883txc4u09igbu77g6 FOREIGN KEY (merchant_id) REFERENCES public.merchants(id);


--
-- Name: credit_consents fkcxhnwq4i8j8k5icknihqwsr73; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_consents
    ADD CONSTRAINT fkcxhnwq4i8j8k5icknihqwsr73 FOREIGN KEY (merchant_id) REFERENCES public.merchants(id);


--
-- Name: disputes fkey6gywkxksuh8fjx3sllv6qad; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disputes
    ADD CONSTRAINT fkey6gywkxksuh8fjx3sllv6qad FOREIGN KEY (payment_id) REFERENCES public.payments(id);


--
-- Name: orders fkg2540vs5sg5b0uov81t6p0229; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fkg2540vs5sg5b0uov81t6p0229 FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: invoices fkhr2junyb6jf0rvv59ujh2e2so; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT fkhr2junyb6jf0rvv59ujh2e2so FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: fee_transactions fkiyupckp3g9e0apy3r38gxg48o; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_transactions
    ADD CONSTRAINT fkiyupckp3g9e0apy3r38gxg48o FOREIGN KEY (merchant_id) REFERENCES public.merchants(id);


--
-- Name: credit_consents fkj04554095mst0m6ptdft480xl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_consents
    ADD CONSTRAINT fkj04554095mst0m6ptdft480xl FOREIGN KEY (lender_id) REFERENCES public.lenders(id);


--
-- Name: complaints fklmc0ccpc9u8mf7douwesp2ix3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.complaints
    ADD CONSTRAINT fklmc0ccpc9u8mf7douwesp2ix3 FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: advances fkn8sljd59xvcm72ujn7ujxoekm; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.advances
    ADD CONSTRAINT fkn8sljd59xvcm72ujn7ujxoekm FOREIGN KEY (lender_id) REFERENCES public.lenders(id);


--
-- Name: credit_assessments fkp8xqs28odv8frwj82no2v1upl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_assessments
    ADD CONSTRAINT fkp8xqs28odv8frwj82no2v1upl FOREIGN KEY (merchant_id) REFERENCES public.merchants(id);


--
-- Name: supplier_catalogue_items fkt15ytv5ojja8bx8ev6xvp4vy2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_catalogue_items
    ADD CONSTRAINT fkt15ytv5ojja8bx8ev6xvp4vy2 FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);


--
-- Name: complaints fktpscgn1rpwcf9l9nbmnkw8cyl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.complaints
    ADD CONSTRAINT fktpscgn1rpwcf9l9nbmnkw8cyl FOREIGN KEY (merchant_id) REFERENCES public.merchants(id);


--
-- Name: orders fktqvpn5gy0ajx8fip3deoidfxd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fktqvpn5gy0ajx8fip3deoidfxd FOREIGN KEY (merchant_id) REFERENCES public.merchants(id);


--
-- Name: identity_links identity_links_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.identity_links
    ADD CONSTRAINT identity_links_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: kyc_application_submissions kyc_application_submissions_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_application_submissions
    ADD CONSTRAINT kyc_application_submissions_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.kyc_applications(id);


--
-- Name: kyc_applications kyc_applications_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_applications
    ADD CONSTRAINT kyc_applications_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);


--
-- Name: kyc_applications kyc_applications_profile_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_applications
    ADD CONSTRAINT kyc_applications_profile_id_fkey FOREIGN KEY (profile_id) REFERENCES public.kyc_profiles(id);


--
-- Name: kyc_applications kyc_applications_schema_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_applications
    ADD CONSTRAINT kyc_applications_schema_version_id_fkey FOREIGN KEY (schema_version_id) REFERENCES public.kyc_schema_versions(id);


--
-- Name: kyc_fields kyc_fields_section_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_fields
    ADD CONSTRAINT kyc_fields_section_id_fkey FOREIGN KEY (section_id) REFERENCES public.kyc_sections(id);


--
-- Name: kyc_list_items kyc_list_items_list_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_list_items
    ADD CONSTRAINT kyc_list_items_list_code_fkey FOREIGN KEY (list_code) REFERENCES public.kyc_lists(code);


--
-- Name: kyc_profiles kyc_profiles_schema_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_profiles
    ADD CONSTRAINT kyc_profiles_schema_version_id_fkey FOREIGN KEY (schema_version_id) REFERENCES public.kyc_schema_versions(id);


--
-- Name: kyc_rules kyc_rules_schema_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_rules
    ADD CONSTRAINT kyc_rules_schema_version_id_fkey FOREIGN KEY (schema_version_id) REFERENCES public.kyc_schema_versions(id);


--
-- Name: kyc_search_index kyc_search_index_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_search_index
    ADD CONSTRAINT kyc_search_index_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.kyc_applications(id);


--
-- Name: kyc_sections kyc_sections_schema_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_sections
    ADD CONSTRAINT kyc_sections_schema_version_id_fkey FOREIGN KEY (schema_version_id) REFERENCES public.kyc_schema_versions(id);


--
-- Name: kyc_verifications kyc_verifications_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_verifications
    ADD CONSTRAINT kyc_verifications_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.kyc_applications(id);


--
-- Name: kyc_workflow_tasks kyc_workflow_tasks_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kyc_workflow_tasks
    ADD CONSTRAINT kyc_workflow_tasks_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.kyc_applications(id);


--
-- Name: org_role_ceilings org_role_ceilings_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_role_ceilings
    ADD CONSTRAINT org_role_ceilings_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);


--
-- Name: suspensions suspensions_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.suspensions
    ADD CONSTRAINT suspensions_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);


--
-- Name: user_platform_functions user_platform_functions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_platform_functions
    ADD CONSTRAINT user_platform_functions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: users users_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);


--
-- PostgreSQL database dump complete
--
