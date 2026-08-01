// Gateway Configuration Web Interface

const CONFIG_FIELDS = [
    'sip_server', 'sip_port', 'sip_user', 'sip_password',
    'use_tls', 'sip_realm', 'sim1_destination', 'sim2_destination',
    'audio_profile', 'audio_card', 'audio_route', 'tx_gain', 'rx_gain', 'mute_preset',
    'manual_mute_controls'
];

// Store detected controls and selected state
let detectedControls = [];
let selectedMuteControls = new Set();

// Load current configuration
async function loadConfig() {
    try {
        const resp = await fetch('/api/config');
        const data = await resp.json();

        // Populate form fields
        document.getElementById('sip_server').value = data.sip_server || '';
        document.getElementById('sip_port').value = data.sip_port || 5060;
        document.getElementById('sip_user').value = data.sip_user || '';
        document.getElementById('sip_password').value = data.sip_password || '';
        document.getElementById('use_tls').checked = data.use_tls || false;
        document.getElementById('sip_realm').value = data.sip_realm || '';
        document.getElementById('sim1_destination').value = data.sim1_destination || '';
        document.getElementById('sim2_destination').value = data.sim2_destination || '';
        document.getElementById('audio_profile').value = data.audio_profile || 'auto';
        document.getElementById('audio_card').value = data.audio_card || 0;
        document.getElementById('audio_route').value = data.audio_route || 'MultiMedia1';
        document.getElementById('tx_gain').value = data.tx_gain !== undefined ? data.tx_gain : 0;
        document.getElementById('rx_gain').value = data.rx_gain !== undefined ? data.rx_gain : 0;
        document.getElementById('mute_preset').value = data.mute_preset || 'redmi_note_7';

        // Populate mute presets dropdown if available
        if (data.available_presets) {
            const select = document.getElementById('mute_preset');
            select.innerHTML = '';
            data.available_presets.forEach(preset => {
                const opt = document.createElement('option');
                opt.value = preset;
                opt.textContent = formatPresetName(preset);
                select.appendChild(opt);
            });
            select.value = data.mute_preset || 'redmi_note_7';
        }

        // Load selected mute controls
        if (data.selected_mute_controls) {
            selectedMuteControls = new Set(data.selected_mute_controls);
        }

        // Load manual mute controls
        document.getElementById('manual_mute_controls').value = data.manual_mute_controls || '';

        // Show/hide custom section
        updateCustomSection();
    } catch (e) {
        console.error('Failed to load config:', e);
        showStatus('Failed to load configuration', 'error');
    }
}

// Format preset name for display
function formatPresetName(preset) {
    const names = {
        'redmi_note_7': 'Redmi Note 7 (SDM660)',
        'generic': 'Generic (SDM4xx)',
        'redmi_4x': 'Redmi 4X (SD435)',
        'custom': 'Custom'
    };
    return names[preset] || preset;
}

// Update custom mute section visibility and load controls
function updateCustomSection() {
    const preset = document.getElementById('mute_preset').value;
    const customSection = document.getElementById('customMuteSection');

    if (preset === 'custom') {
        customSection.style.display = 'block';
        loadMixerControls();
    } else {
        customSection.style.display = 'none';
    }
}

// Load mixer controls from API
async function loadMixerControls() {
    const container = document.getElementById('detectedControls');
    container.innerHTML = '<small>Loading controls...</small>';

    try {
        const card = document.getElementById('audio_card').value || 0;
        const resp = await fetch(`/api/mixer-controls?card=${card}`);
        const data = await resp.json();

        if (data.error) {
            container.innerHTML = `<small>Error: ${data.error}</small>`;
            return;
        }

        detectedControls = data.controls || [];

        if (detectedControls.length === 0) {
            container.innerHTML = '<small>No controls detected. Make sure the device has root access.</small>';
            return;
        }

        // Build checkbox list
        container.innerHTML = '';
        detectedControls.forEach(ctrl => {
            const label = document.createElement('label');
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.value = ctrl.name;
            checkbox.checked = selectedMuteControls.has(ctrl.name);
            checkbox.addEventListener('change', (e) => {
                if (e.target.checked) {
                    selectedMuteControls.add(ctrl.name);
                } else {
                    selectedMuteControls.delete(ctrl.name);
                }
            });

            const text = document.createElement('span');
            text.textContent = `${ctrl.name} (${ctrl.value}) - ${ctrl.type}`;

            label.appendChild(checkbox);
            label.appendChild(text);
            container.appendChild(label);
        });
    } catch (e) {
        console.error('Failed to load mixer controls:', e);
        container.innerHTML = `<small>Failed to load: ${e.message}</small>`;
    }
}

// Save configuration
async function saveConfig(e) {
    e.preventDefault();

    const btn = document.getElementById('applyBtn');
    btn.disabled = true;
    showStatus('Saving...', 'loading');

    const data = {
        sip_server: document.getElementById('sip_server').value,
        sip_port: parseInt(document.getElementById('sip_port').value) || 5060,
        sip_user: document.getElementById('sip_user').value,
        sip_password: document.getElementById('sip_password').value,
        use_tls: document.getElementById('use_tls').checked,
        sip_realm: document.getElementById('sip_realm').value,
        sim1_destination: document.getElementById('sim1_destination').value,
        sim2_destination: document.getElementById('sim2_destination').value,
        audio_profile: document.getElementById('audio_profile').value,
        audio_card: parseInt(document.getElementById('audio_card').value) || 0,
        audio_route: document.getElementById('audio_route').value,
        tx_gain: parseFloat(document.getElementById('tx_gain').value) || 0,
        rx_gain: parseFloat(document.getElementById('rx_gain').value) || 0,
        mute_preset: document.getElementById('mute_preset').value,
        selected_mute_controls: Array.from(selectedMuteControls),
        manual_mute_controls: document.getElementById('manual_mute_controls').value
    };

    try {
        const resp = await fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });

        const result = await resp.json();

        if (result.status === 'ok') {
            showStatus(result.message || 'Configuration saved!', 'success');
        } else {
            showStatus(result.error || 'Unknown error', 'error');
        }
    } catch (e) {
        showStatus('Failed to save: ' + e.message, 'error');
    }

    btn.disabled = false;
}

// Disable web interface
async function disableInterface() {
    if (!confirm('Disable web interface? You will need to re-enable it from the app.')) {
        return;
    }

    showStatus('Disabling...', 'loading');

    try {
        const resp = await fetch('/api/disable', { method: 'POST' });
        const result = await resp.json();
        showStatus(result.message || 'Web interface disabled', 'success');
    } catch (e) {
        showStatus('Failed: ' + e.message, 'error');
    }
}

// Toggle password visibility
function togglePassword() {
    const pwd = document.getElementById('sip_password');
    const toggle = document.getElementById('togglePwd');

    if (pwd.type === 'password') {
        pwd.type = 'text';
        toggle.innerHTML = '&#128584;'; // See-no-evil monkey
    } else {
        pwd.type = 'password';
        toggle.innerHTML = '&#128065;'; // Eye
    }
}

// Show status message
function showStatus(message, type) {
    const status = document.getElementById('status');
    status.className = type;
    status.textContent = message;
}

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    // Load config
    loadConfig();

    // Setup event listeners
    document.getElementById('configForm').addEventListener('submit', saveConfig);
    document.getElementById('disableBtn').addEventListener('click', disableInterface);
    document.getElementById('togglePwd').addEventListener('click', togglePassword);

    // Mute preset change handler
    document.getElementById('mute_preset').addEventListener('change', updateCustomSection);

    // Refresh controls button
    document.getElementById('refreshControlsBtn').addEventListener('click', loadMixerControls);

    // Reload controls when audio card changes
    document.getElementById('audio_card').addEventListener('change', () => {
        if (document.getElementById('mute_preset').value === 'custom') {
            loadMixerControls();
        }
    });
});
