FROM python:3.12-slim

WORKDIR /app
COPY asset/scripts/requirements-k8s.txt ./requirements-k8s.txt
RUN pip install --no-cache-dir -r requirements-k8s.txt
COPY asset/scripts/collect_k8s_metrics.py ./collect_k8s_metrics.py

USER 10001:10001
ENTRYPOINT ["python", "/app/collect_k8s_metrics.py", "--in-cluster", "--namespace", "ecomm", "--output", "-"]
