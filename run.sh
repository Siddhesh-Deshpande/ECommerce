#!/bin/bash

# Delete namespace (ignore error if it doesn't exist)
kubectl delete ns ecomm --ignore-not-found

# Create namespace
kubectl create ns ecomm

# Set current context namespace
kubectl config set-context --current --namespace=ecomm

# Apply configurations
kubectl apply -f statefulsets-deployment/
kubectl apply -f monitoring/
kubectl apply -f service-deployment/

echo "Deployment completed in namespace ecomm"