const reportDialog = document.getElementById('catalogReport');
const reportForm = document.getElementById('catalogReportForm');
const reportStatus = document.getElementById('reportStatus');
let reportPending = false;

document.addEventListener('click', event => {
  const button = event.target.closest('[data-report-id]');
  if (!button || reportPending) return;
  reportForm.reset();
  reportForm.elements.targetId.value = button.dataset.reportId;
  reportForm.elements.targetType.value = button.dataset.reportType;
  document.getElementById('reportTarget').textContent = button.dataset.reportName;
  reportStatus.textContent = '';
  reportDialog.showModal();
});
document.getElementById('reportCancel').addEventListener('click', () => reportDialog.close());
reportForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (reportPending || !reportForm.reportValidity()) return;
  reportPending = true;
  const submit = reportForm.querySelector('[type=submit]');
  submit.disabled = true;
  reportStatus.textContent = '제보를 보내고 있어요…';
  const endpoint = base();
  try {
    const csrfResponse = await fetch(endpoint + '/api/v1/auth/csrf', {credentials: 'include'});
    if (!csrfResponse.ok) throw new Error('제보를 준비하지 못했어요. 잠시 후 다시 시도해 주세요.');
    const csrf = (await csrfResponse.json()).data;
    const fields = reportForm.elements;
    const response = await fetch(endpoint + '/api/v1/catalog/reports', {
      method: 'POST', credentials: 'include',
      headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token},
      body: JSON.stringify({targetType: fields.targetType.value, targetId: Number(fields.targetId.value),
        field: fields.field.value, description: fields.description.value, sourceUrl: fields.sourceUrl.value})
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error?.message || '제보를 보내지 못했어요. 다시 시도해 주세요.');
    reportStatus.textContent = '접수했어요. 내용을 확인한 뒤 정보를 수정하겠습니다.';
    reportForm.querySelector('[name=description]').value = '';
  } catch (error) {
    reportStatus.textContent = error.message || '연결을 확인하고 다시 시도해 주세요.';
  } finally {
    reportPending = false;
    submit.disabled = false;
  }
});
