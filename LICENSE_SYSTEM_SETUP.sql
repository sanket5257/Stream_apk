-- ============================================================================
--  StreamForge — LICENCE SYSTEM
--
--  Run this ONCE in the Supabase SQL Editor (Dashboard → SQL Editor → New query).
--  It creates the licences table and the two functions the app calls.
--
--  After this is installed you manage everything from the Supabase dashboard:
--    Table Editor → licenses   (issue, extend, revoke — no code, no deploy)
--
--  There is no payment gateway. You take payment however you like, then hand the
--  customer a code from this table.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. The licences table
-- ----------------------------------------------------------------------------
create table if not exists public.licenses (
    id              uuid primary key default gen_random_uuid(),

    -- The code you give the customer. Uppercase, no spaces (the app normalises input
    -- to match, so "sf-pro-a1b2" typed in lowercase still works).
    code            text not null unique,

    -- FREE / PRO / STUDIO. Must match the Tier enum in the app exactly.
    tier            text not null default 'PRO'
                    check (tier in ('FREE', 'PRO', 'STUDIO')),

    -- How long the licence runs once activated. 0 = never expires (a lifetime licence).
    duration_days   integer not null default 30,

    -- Your own notes: who bought it, what they paid, phone number. Free text, only
    -- ever read by you in the dashboard.
    issued_to       text,
    notes           text,

    -- Filled in automatically when the customer activates the code in the app.
    user_id         uuid references public.users(id) on delete set null,
    device_id       text,
    activated_at    timestamptz,
    expires_at      timestamptz,

    -- Set to false to REVOKE. The app drops to Free the next time it checks in
    -- (within the 7-day offline grace window).
    is_active       boolean not null default true,

    created_at      timestamptz not null default now()
);

create index if not exists licenses_code_idx    on public.licenses (code);
create index if not exists licenses_user_idx    on public.licenses (user_id);
create index if not exists licenses_active_idx  on public.licenses (is_active);

-- Lock the table down. The app's anon key must NOT be able to read or write licences
-- directly — everything goes through the SECURITY DEFINER functions below, exactly
-- like the existing auth functions. Without this, anyone with the anon key (which
-- ships inside the APK) could simply list every valid code.
alter table public.licenses enable row level security;
revoke all on public.licenses from anon, authenticated;


-- ----------------------------------------------------------------------------
-- 2. Activate a code  →  called when the customer types their code into the app
-- ----------------------------------------------------------------------------
create or replace function public.app_activate_license(
    p_user_id   uuid,
    p_device_id text,
    p_code      text
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    v_license   public.licenses%rowtype;
    v_expires   timestamptz;
begin
    if p_user_id is null or p_device_id is null or p_code is null then
        return json_build_object('status', 'error', 'message', 'Missing details. Log in again and retry.');
    end if;

    select * into v_license
    from public.licenses
    where upper(code) = upper(trim(p_code))
    limit 1;

    if not found then
        return json_build_object('status', 'error', 'message', 'That code doesn''t exist. Check it and try again.');
    end if;

    if not v_license.is_active then
        return json_build_object('status', 'error', 'message', 'That code has been deactivated. Contact us.');
    end if;

    -- Already bound to somebody else? Codes are one customer, one device. This is what
    -- stops a single code being shared around a WhatsApp group.
    if v_license.user_id is not null and v_license.user_id <> p_user_id then
        return json_build_object('status', 'error', 'message', 'That code is already in use on another account.');
    end if;

    if v_license.device_id is not null and v_license.device_id <> p_device_id then
        return json_build_object('status', 'error', 'message',
            'That code is registered to a different device. Contact us to move it.');
    end if;

    -- Compute the expiry on FIRST activation only, so re-entering the same code on the
    -- same device doesn't silently extend the term.
    if v_license.activated_at is null then
        v_expires := case
            when v_license.duration_days <= 0 then null
            else now() + make_interval(days => v_license.duration_days)
        end;

        update public.licenses
        set user_id      = p_user_id,
            device_id    = p_device_id,
            activated_at = now(),
            expires_at   = v_expires
        where id = v_license.id
        returning * into v_license;
    end if;

    -- Expired already?
    if v_license.expires_at is not null and v_license.expires_at < now() then
        return json_build_object('status', 'error', 'message', 'That licence has expired. Contact us to renew.');
    end if;

    return json_build_object(
        'status',     'ok',
        'tier',       v_license.tier,
        'expires_at', v_license.expires_at
    );
end;
$$;


-- ----------------------------------------------------------------------------
-- 3. Check the licence  →  called quietly on app start
-- ----------------------------------------------------------------------------
create or replace function public.app_check_license(
    p_user_id   uuid,
    p_device_id text
)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
    v_license public.licenses%rowtype;
begin
    -- The best still-valid licence for this user on this device. Ordered so a STUDIO
    -- licence wins over a PRO one if somebody holds both.
    select * into v_license
    from public.licenses
    where user_id = p_user_id
      and device_id = p_device_id
      and is_active = true
      and (expires_at is null or expires_at > now())
    order by case tier when 'STUDIO' then 3 when 'PRO' then 2 else 1 end desc,
             expires_at desc nulls first
    limit 1;

    if not found then
        return json_build_object('status', 'none', 'tier', 'FREE');
    end if;

    return json_build_object(
        'status',     'ok',
        'tier',       v_license.tier,
        'expires_at', v_license.expires_at
    );
end;
$$;


-- ----------------------------------------------------------------------------
-- 4. Let the app (anon key) call ONLY these two functions
-- ----------------------------------------------------------------------------
grant execute on function public.app_activate_license(uuid, text, text) to anon, authenticated;
grant execute on function public.app_check_license(uuid, text)          to anon, authenticated;


-- ----------------------------------------------------------------------------
-- 5. Helper: generate a batch of codes
--
--    Run this whenever you need new codes to sell. Then read them out of the
--    Table Editor and send them to customers.
--
--    Example — ten 30-day PRO codes:
--      select * from public.generate_licenses('PRO', 30, 10);
-- ----------------------------------------------------------------------------
create or replace function public.generate_licenses(
    p_tier          text default 'PRO',
    p_duration_days integer default 30,
    p_count         integer default 1
)
returns table (code text, tier text, duration_days integer)
language plpgsql
security definer
set search_path = public
as $$
declare
    i          integer;
    v_code     text;
    -- No 0/O/1/I: these get read out over the phone and written down by hand.
    v_alphabet text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    v_body     text;
    j          integer;
begin
    for i in 1..greatest(p_count, 1) loop
        loop
            v_body := '';
            for j in 1..8 loop
                v_body := v_body || substr(v_alphabet, 1 + floor(random() * length(v_alphabet))::int, 1);
            end loop;
            v_code := 'SF-' || upper(p_tier) || '-' || v_body;
            exit when not exists (select 1 from public.licenses l where l.code = v_code);
        end loop;

        insert into public.licenses (code, tier, duration_days)
        values (v_code, upper(p_tier), p_duration_days);

        code := v_code;
        tier := upper(p_tier);
        duration_days := p_duration_days;
        return next;
    end loop;
end;
$$;


-- ============================================================================
--  EVERYDAY USE — all from the Supabase dashboard, no code
-- ============================================================================
--
--  Make 10 monthly Pro codes:
--      select * from public.generate_licenses('PRO', 30, 10);
--
--  Make 5 yearly Studio codes:
--      select * from public.generate_licenses('STUDIO', 365, 5);
--
--  Make one lifetime Pro code (duration 0 = never expires):
--      select * from public.generate_licenses('PRO', 0, 1);
--
--  See who is using what:
--      select code, tier, issued_to, activated_at, expires_at, is_active
--      from public.licenses order by created_at desc;
--
--  Revoke a licence (they get 7 more days offline, then drop to Free):
--      update public.licenses set is_active = false where code = 'SF-PRO-ABCD1234';
--
--  Extend / renew:
--      update public.licenses set expires_at = expires_at + interval '30 days'
--      where code = 'SF-PRO-ABCD1234';
--
--  Move a licence to a new phone (customer changed device):
--      update public.licenses set device_id = null where code = 'SF-PRO-ABCD1234';
--      -- then they re-enter the same code on the new phone
--
--  Record who bought it (do this when you send the code):
--      update public.licenses
--      set issued_to = 'Ramesh — 98xxxxxx21', notes = 'UPI ₹499, 6 Sep'
--      where code = 'SF-PRO-ABCD1234';
-- ============================================================================
