# Airflow Integration

DataHub supports integration of

- Airflow Pipeline (DAG) metadata
- DAG and Task run information as well as
- Lineage information when present

You can use either the DataHub Airflow lineage plugin (recommended) or the Airflow lineage backend (deprecated).

## Using Datahub's Airflow lineage plugin

:::note

The Airflow lineage plugin is only supported with Airflow version >= 2.0.2 or on MWAA with an Airflow version >= 2.0.2.

If you're using Airflow 1.x, use the Airflow lineage plugin with acryl-datahub-airflow-plugin <= 0.9.1.0.

:::

This plugin registers a task success/failure callback on every task with a cluster policy and emits DataHub events from that. This allows this plugin to be able to register both task success as well as failures compared to the older Airflow Lineage Backend which could only support emitting task success.

### Setup

1. You need to install the required dependency in your airflow.

```shell
pip install acryl-datahub-airflow-plugin
```

:::note

The [DataHub Rest](../../metadata-ingestion/sink_docs/datahub.md#datahub-rest) emitter is included in the plugin package by default. To use [DataHub Kafka](../../metadata-ingestion/sink_docs/datahub.md#datahub-kafka) install `pip install acryl-datahub-airflow-plugin[datahub-kafka]`.

:::

2. Disable lazy plugin loading in your airflow.cfg.
   On MWAA you should add this config to your [Apache Airflow configuration options](https://docs.aws.amazon.com/mwaa/latest/userguide/configuring-env-variables.html#configuring-2.0-airflow-override).

```ini title="airflow.cfg"
[core]
lazy_load_plugins = False
```

3. You must configure an Airflow hook for Datahub. We support both a Datahub REST hook and a Kafka-based hook, but you only need one.

   ```shell
   # For REST-based:
   airflow connections add  --conn-type 'datahub_rest' 'datahub_rest_default' --conn-host 'http://datahub-gms:8080' --conn-password '<optional datahub auth token>'
   # For Kafka-based (standard Kafka sink config can be passed via extras):
   airflow connections add  --conn-type 'datahub_kafka' 'datahub_kafka_default' --conn-host 'broker:9092' --conn-extra '{}'
   ```

4. Add your `datahub_conn_id` and/or `cluster` to your `airflow.cfg` file if it is not align with the default values. See configuration parameters below

   **Configuration options:**

   | Name                           | Default value        | Description                                                                                                                                                                            |
   | ------------------------------ | -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
   | datahub.enabled                | true                 | If the plugin should be enabled.                                                                                                                                                       |
   | datahub.conn_id                | datahub_rest_default | The name of the datahub connection you set in step 1.                                                                                                                                  |
   | datahub.cluster                | prod                 | name of the airflow cluster                                                                                                                                                            |
   | datahub.capture_ownership_info | true                 | If true, the owners field of the DAG will be capture as a DataHub corpuser.                                                                                                            |
   | datahub.capture_tags_info      | true                 | If true, the tags field of the DAG will be captured as DataHub tags.                                                                                                                   |
   | datahub.graceful_exceptions    | true                 | If set to true, most runtime errors in the lineage backend will be suppressed and will not cause the overall task to fail. Note that configuration issues will still throw exceptions. |

5. Configure `inlets` and `outlets` for your Airflow operators. For reference, look at the sample DAG in [`lineage_backend_demo.py`](../../metadata-ingestion/src/datahub_provider/example_dags/lineage_backend_demo.py), or reference [`lineage_backend_taskflow_demo.py`](../../metadata-ingestion/src/datahub_provider/example_dags/lineage_backend_taskflow_demo.py) if you're using the [TaskFlow API](https://airflow.apache.org/docs/apache-airflow/stable/concepts/taskflow.html).
6. [optional] Learn more about [Airflow lineage](https://airflow.apache.org/docs/apache-airflow/stable/lineage.html), including shorthand notation and some automation.

### How to validate installation

1. Go and check in Airflow at Admin -> Plugins menu if you can see the DataHub plugin
2. Run an Airflow DAG. In the task logs, you should see Datahub related log messages like:

```
Emitting DataHub ...
```

### Emitting lineage via a custom operator to the Airflow Plugin

If you have created a custom Airflow operator [docs](https://airflow.apache.org/docs/apache-airflow/stable/howto/custom-operator.html) that inherits from the BaseOperator class,
when overriding the `execute` function, set inlets and outlets via `context['ti'].task.inlets` and `context['ti'].task.outlets`.
The DataHub Airflow plugin will then pick up those inlets and outlets after the task runs. 



```python
class DbtOperator(BaseOperator):
    ...

    def execute(self, context):
        # do something
        inlets, outlets = self._get_lineage()
        # inlets/outlets are lists of either datahub_provider.entities.Dataset or datahub_provider.entities.Urn
        context['ti'].task.inlets = self.inlets
        context['ti'].task.outlets = self.outlets

    def _get_lineage(self):
        # Do some processing to get inlets/outlets
        
        return inlets, outlets 
```

If you override the `pre_execute` and `post_execute` function, ensure they include the `@prepare_lineage` and `@apply_lineage` decorators respectively. [source](https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/lineage.html#lineage)

## Using DataHub's Airflow lineage backend (deprecated)

:::caution

The DataHub Airflow plugin (above) is the recommended way to integrate Airflow with DataHub. For managed services like MWAA, the lineage backend is not supported and so you must use the Airflow plugin.

If you're using Airflow 1.x, we recommend using the Airflow lineage backend with acryl-datahub <= 0.9.1.0.

:::

:::note

If you are looking to run Airflow and DataHub using docker locally, follow the guide [here](../../docker/airflow/local_airflow.md). Otherwise proceed to follow the instructions below.
:::

### Setting up Airflow to use DataHub as Lineage Backend

1. You need to install the required dependency in your airflow. See <https://registry.astronomer.io/providers/datahub/modules/datahublineagebackend>

```shell
pip install acryl-datahub[airflow]
# If you need the Kafka-based emitter/hook:
pip install acryl-datahub[airflow,datahub-kafka]
```

2. You must configure an Airflow hook for Datahub. We support both a Datahub REST hook and a Kafka-based hook, but you only need one.

   ```shell
   # For REST-based:
   airflow connections add  --conn-type 'datahub_rest' 'datahub_rest_default' --conn-host 'http://datahub-gms:8080' --conn-password '<optional datahub auth token>'
   # For Kafka-based (standard Kafka sink config can be passed via extras):
   airflow connections add  --conn-type 'datahub_kafka' 'datahub_kafka_default' --conn-host 'broker:9092' --conn-extra '{}'
   ```

3. Add the following lines to your `airflow.cfg` file.

   ```ini title="airflow.cfg"
   [lineage]
   backend = datahub_provider.lineage.datahub.DatahubLineageBackend
   datahub_kwargs = {
       "enabled": true,
       "datahub_conn_id": "datahub_rest_default",
       "cluster": "prod",
       "capture_ownership_info": true,
       "capture_tags_info": true,
       "graceful_exceptions": true }
   # The above indentation is important!
   ```

   **Configuration options:**

   - `datahub_conn_id` (required): Usually `datahub_rest_default` or `datahub_kafka_default`, depending on what you named the connection in step 1.
   - `cluster` (defaults to "prod"): The "cluster" to associate Airflow DAGs and tasks with.
   - `capture_ownership_info` (defaults to true): If true, the owners field of the DAG will be capture as a DataHub corpuser.
   - `capture_tags_info` (defaults to true): If true, the tags field of the DAG will be captured as DataHub tags.
   - `capture_executions` (defaults to false): If true, it captures task runs as DataHub DataProcessInstances.
   - `graceful_exceptions` (defaults to true): If set to true, most runtime errors in the lineage backend will be suppressed and will not cause the overall task to fail. Note that configuration issues will still throw exceptions.

4. Configure `inlets` and `outlets` for your Airflow operators. For reference, look at the sample DAG in [`lineage_backend_demo.py`](../../metadata-ingestion/src/datahub_provider/example_dags/lineage_backend_demo.py), or reference [`lineage_backend_taskflow_demo.py`](../../metadata-ingestion/src/datahub_provider/example_dags/lineage_backend_taskflow_demo.py) if you're using the [TaskFlow API](https://airflow.apache.org/docs/apache-airflow/stable/concepts/taskflow.html).
5. [optional] Learn more about [Airflow lineage](https://airflow.apache.org/docs/apache-airflow/stable/lineage.html), including shorthand notation and some automation.

## Emitting lineage via a separate operator

Take a look at this sample DAG:

- [`lineage_emission_dag.py`](../../metadata-ingestion/src/datahub_provider/example_dags/lineage_emission_dag.py) - emits lineage using the DatahubEmitterOperator.

In order to use this example, you must first configure the Datahub hook. Like in ingestion, we support a Datahub REST hook and a Kafka-based hook. See step 1 above for details.


## Debugging

### Incorrect URLs

If your URLs aren't being generated correctly (usually they'll start with `http://localhost:8080` instead of the correct hostname), you may need to set the webserver `base_url` config.

```ini title="airflow.cfg"
[webserver]
base_url = http://airflow.example.com
```

## Additional references

Related Datahub videos:

- [Airflow Lineage](https://www.youtube.com/watch?v=3wiaqhb8UR0)
- [Airflow Run History in DataHub](https://www.youtube.com/watch?v=YpUOqDU5ZYg)
