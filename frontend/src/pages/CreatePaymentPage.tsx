import { useNavigate } from "react-router-dom";
import { PageHeading } from "../components/PageHeading";
import { PaymentForm } from "../features/payments/PaymentForm";
export function CreatePaymentPage() {
  const navigate = useNavigate();
  return (
    <>
      <PageHeading
        title="Create a payment"
        subtitle="Submit once. Follow the result as PayFlow processes it."
      />
      <PaymentForm
        onCreated={(p) =>
          navigate(`/payments/${p.id}`, { state: { submitted: true } })
        }
      />
    </>
  );
}
