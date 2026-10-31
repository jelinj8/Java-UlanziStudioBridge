const form = document.querySelector('#property-inspector');
const navPage = document.querySelector('#nav-page');

function showSettings(settings) {
	for (const name of ['appId', 'slotId', 'navMode', 'navPage'])
		form.elements[name].value = settings[name] ?? '';
	updateNavPage();
}

function updateNavPage() {
	navPage.classList.toggle('hidden', form.elements.navMode.value !== 'page');
}

$UD.connect();

$UD.onConnected(() => {
	form.classList.remove('hidden');
	form.addEventListener('input', Utils.debounce(() => {
		updateNavPage();
		$UD.sendParamFromPlugin({
			appId: form.elements.appId.value,
			slotId: form.elements.slotId.value,
			navMode: form.elements.navMode.value,
			navPage: form.elements.navPage.value,
		});
	}));
});

// Studio can deliver the saved settings through either event
$UD.onAdd((msg) => {
	if (msg?.param)
		showSettings(msg.param);
});

$UD.onParamFromApp((msg) => {
	if (msg?.param)
		showSettings(msg.param);
});
