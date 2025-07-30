# Reputul Backend

Reputul Backend is a Spring Boot-based application designed to manage customer feedback, email notifications, and review requests for businesses. The project is structured to support a monolithic architecture but is being refactored into microservices for scalability and maintainability.

## Features

- **Customer Feedback Management**: Collect and store customer feedback with ratings and comments.
- **Email Notifications**: Send review requests, follow-up emails, and thank-you emails using SendGrid.
- **Template Management**: Dynamically generate email content using customizable templates.
- **Review Links**: Generate Google, Facebook, and private review links for customers.
- **Health Check**: Built-in health check endpoints for monitoring service status.

## Technologies Used

- **Java**: Core programming language.
- **Spring Boot**: Framework for building the backend application.
- **Maven**: Dependency and build management.
- **SendGrid**: Email delivery service.
- **H2/Other Databases**: Database for storing customer, business, and review data.
- **Lombok**: Simplifies Java code with annotations.
- **Docker**: Containerization for deployment (optional).

## Endpoints

### Customer Feedback
- **GET** `/api/customers/{customerId}/feedback-info`: Retrieve customer and business information for feedback.
- **POST** `/api/customers/{customerId}/feedback`: Submit customer feedback.
- **GET** `/api/customers/{customerId}/test`: Test endpoint to verify customer data.
- **GET** `/api/customers/feedback/health`: Health check endpoint.

### Email Management
- **Email Sending**: Handled by `EmailService` with support for review requests, follow-ups, and thank-you emails.

## How to Run

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/your-repo/reputul-backend.git
   cd reputul-backend


