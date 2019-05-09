use crate::metrics::Metric;
use crate::CommonMetricData;
use crate::Glean;

#[derive(Debug)]
pub struct UuidMetric {
    meta: CommonMetricData,
}

impl UuidMetric {
    pub fn new(meta: CommonMetricData) -> Self {
        Self { meta }
    }

    pub fn set(&self, glean: &Glean, value: uuid::Uuid) {
        if !self.meta.should_record() || !glean.is_upload_enabled() {
            return;
        }

        let s = value.to_string();
        let value = Metric::Uuid(s);
        glean.storage().record(&self.meta, &value)
    }

    pub fn generate(&self, storage: &Glean) -> uuid::Uuid {
        let uuid = uuid::Uuid::new_v4();
        self.set(storage, uuid);
        uuid
    }

    pub fn generate_if_missing(&self, glean: &Glean) {
        if !self.meta.should_record() || !glean.is_upload_enabled() {
            return;
        }

        glean
            .storage()
            .record_with(&self.meta, |old_value| match old_value {
                Some(Metric::Uuid(old_value)) => Metric::Uuid(old_value),
                _ => {
                    let uuid = uuid::Uuid::new_v4();
                    let new_value = uuid.to_string();
                    Metric::Uuid(new_value)
                }
            })
    }
}